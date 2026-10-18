/*
 * The server, started and kept running: docker/orknux-run.sh, in Node.
 *
 * In the images that script is PID 1 and this is its job: choose a jar, start
 * it, and when it exits decide whether that was a stop, a request to be started
 * again, or a release that failed and has to be fallen back from. The desktop
 * has no PID 1 to lean on, so the main process is the supervisor - with the same
 * environment and the same exit codes, so that whatever ReleaseLauncher decides
 * on a server, it decides here too, and a jar written for one works in both.
 */
const { spawn, execFile } = require('node:child_process');
const fs = require('node:fs');
const http = require('node:http');
const net = require('node:net');
const path = require('node:path');

/** What a server exits with to ask to be started again, which ReleaseLauncher then chooses a jar for. */
const START_AGAIN = 75;
const PREFERRED_PORT = 8080;
const READY_TIMEOUT_MS = 180_000;
const STOP_GRACE_MS = 15_000;

/** Whether a port can be listened on at that address, asked by listening on it. */
function free(port, host) {
  return new Promise((resolve) => {
    const probe = net.createServer();
    probe.once('error', () => resolve(false));
    probe.listen(port, host, () => probe.close(() => resolve(true)));
  });
}

/** 8080, as every other installation, unless something already has it - then whatever the system hands out. */
async function choosePort(host) {
  if (await free(PREFERRED_PORT, host)) return PREFERRED_PORT;
  return new Promise((resolve, reject) => {
    const probe = net.createServer();
    probe.once('error', reject);
    probe.listen(0, host, () => {
      const { port } = probe.address();
      probe.close(() => resolve(port));
    });
  });
}

/** One GET, answered by its status or by null where nothing answered. */
function status(url) {
  return new Promise((resolve) => {
    const request = http.get(url, { timeout: 3000 }, (response) => {
      response.resume();
      resolve(response.statusCode ?? null);
    });
    request.on('timeout', () => request.destroy());
    request.on('error', () => resolve(null));
  });
}

/**
 * The JVM's options, as orknux-run.sh sets them, minus what only a container
 * has. There is no cgroup to size the heap from, so it is a share of the
 * machine instead - a quarter, because this is one program among a person's
 * others, not the reason the box exists.
 */
function javaOptions(temp, extra) {
  const options = [
    '-XX:MaxRAMPercentage=25',
    '-XX:+ExitOnOutOfMemoryError',
    `-Djdk.nio.maxCachedBufferSize=${256 * 1024}`,
    /*
     * Its own temporary directory, under the data directory. A local box keeps
     * its sessions under java.io.tmpdir/orknux-box, and two installations on one
     * machine sharing the system's temp directory clean up each other's
     * sessions - which has already happened to a development server once.
     */
    `-Djava.io.tmpdir=${temp}`,
  ];
  return options.concat(extra);
}

class Server {
  /**
   * @param {object} given
   * @param {string} given.java     the runtime's java executable
   * @param {string} given.imageJar the jar shipped with this install, which the launcher falls back to
   * @param {boolean} given.selfUpdate whether a release the server installed may run instead of the shipped one
   * @param {import('./logs').Log} given.log
   */
  constructor({ java, imageJar, selfUpdate, log }) {
    this.java = java;
    this.imageJar = imageJar;
    this.selfUpdate = selfUpdate;
    this.log = log;
    this.child = null;
    this.stopping = false;
    this.port = null;
    this.onExit = () => {};
  }

  /**
   * Chooses the address and port and starts the loop. Resolves with the URL the
   * window should load once the server answers, and rejects if it stops first.
   */
  async start({ data, prepared, network }) {
    // Free on every address, whichever this start binds: a later switch to the network keeps the port.
    this.port = await choosePort('0.0.0.0');
    // The window always goes the loopback way, whichever address the server is open on.
    const url = `http://127.0.0.1:${this.port}`;

    const db = prepared.database.split(path.sep).join('/');
    this.env = {
      ...process.env,
      // What Dockerfile.one sets, said for a directory on this machine.
      ORKNUX_DB_URL: `jdbc:sqlite:${db}`,
      ORKNUX_PORT: String(this.port),
      ORKNUX_TEMPORAL_ENABLED: 'false',
      ORKNUX_TEMPORAL_UI_URL: '',
      ORKNUX_ALLOWED_ORIGINS: '',
      ORKNUX_BASE_URL: `http://localhost:${this.port}`,
      ORKNUX_ATTACHMENTS_LOCATION: prepared.attachments,
      ORKNUX_IMAGE_JAR: this.imageJar,
      ORKNUX_DATA: data,
      ORKNUX_SECRET_KEY: prepared.secretKey,
      ORKNUX_AUTH_METHOD: prepared.authMethod,
      // The server's default is /tmp/orknux-release, which is not a directory on Windows and not one that lasts on Linux.
      ORKNUX_RELEASE_DIR: prepared.releases,
      ORKNUX_SELF_UPDATE: this.selfUpdate ? 'true' : 'false',
      // The server's desktop mode: its open-access warning names the person at the machine, and it stops when its input closes.
      ORKNUX_DESKTOP: 'true',
      MALLOC_ARENA_MAX: process.env.MALLOC_ARENA_MAX ?? '2',
    };
    this.apply(prepared, network);
    this.temp = prepared.temp;

    this.loop();
    await this.ready(`${url}/api/auth/method`);
    return url;
  }

  /**
   * Who signs in and where it listens, from what was prepared - at the first
   * start, and again when the server asks to be started again, which is how a
   * change to Network access in Admin › Settings takes effect.
   */
  apply(prepared, network) {
    const host = network === 'exposed' ? '0.0.0.0' : '127.0.0.1';
    this.env.ORKNUX_AUTH_METHOD = prepared.authMethod;
    delete this.env.ORKNUX_BOOTSTRAP_ADMIN_USERNAME;
    delete this.env.ORKNUX_BOOTSTRAP_ADMIN_PASSWORD;
    if (prepared.admin !== null) {
      this.env.ORKNUX_BOOTSTRAP_ADMIN_USERNAME = prepared.admin.username;
      this.env.ORKNUX_BOOTSTRAP_ADMIN_PASSWORD = prepared.admin.password;
    }
    // No ORKNUX_ variable says where to listen; Spring's own argument does.
    this.springArgs = [`--server.address=${host}`];
    this.network = network;
  }

  /** Waits for the server to answer, or for it to stop trying. */
  async ready(probe) {
    const started = Date.now();
    while (Date.now() - started < READY_TIMEOUT_MS) {
      if (this.child === null && !this.restarting) throw new Error('The server stopped before it was ready.');
      if ((await status(probe)) === 200) return;
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
    throw new Error(`The server did not answer within ${READY_TIMEOUT_MS / 1000} seconds.`);
  }

  /**
   * Which jar to run: ReleaseLauncher's answer, the last line it prints, or the
   * shipped jar where it answers nothing usable. Asked before every start, as
   * the script asks, with what the previous start ended in.
   */
  choose(lastExit, lastJar) {
    if (!this.selfUpdate) return Promise.resolve(this.imageJar);
    return new Promise((resolve) => {
      execFile(
        this.java,
        [
          ...javaOptions(this.temp, []),
          '-cp', this.imageJar,
          '-Dloader.main=io.mszymanski.orknux.server.update.ReleaseLauncherKt',
          'org.springframework.boot.loader.launch.PropertiesLauncher',
          this.imageJar,
        ],
        {
          env: { ...this.env, ORKNUX_LAST_EXIT: lastExit ?? '', ORKNUX_LAST_JAR: lastJar ?? '' },
          windowsHide: true,
          maxBuffer: 8 * 1024 * 1024,
        },
        (failure, stdout, stderr) => {
          if (stderr) this.log.server(stderr);
          const lines = (stdout ?? '').trim().split(/\r?\n/);
          const jar = lines[lines.length - 1]?.trim() ?? '';
          if (failure || jar === '' || !fs.existsSync(jar)) {
            if (failure) this.log.note(`the release launcher failed (${failure.message}); running the shipped jar`);
            resolve(this.imageJar);
          } else {
            resolve(jar);
          }
        },
      );
    });
  }

  /** orknux-run.sh's loop: start, wait, and decide what an exit meant. */
  async loop() {
    let lastExit = null;
    let lastJar = null;
    for (;;) {
      this.restarting = true;
      const jar = await this.choose(lastExit, lastJar);
      this.restarting = false;
      if (this.stopping) return;
      this.log.note(`starting ${jar} on port ${this.port}`);
      const code = await this.run(jar);
      if (this.stopping) return;
      if (code === START_AGAIN) {
        this.log.note('the server asked to be started again; choosing a jar');
        // Read again: a change to Network access is applied by exactly this restart.
        const again = this.reconfigure?.();
        if (again) {
          this.apply(again.prepared, again.network);
          this.log.note(`network access: ${again.network}`);
          this.onNetwork?.(again.network);
        }
        lastExit = null;
        lastJar = null;
        this.restarting = true;
        continue;
      }
      if (jar === this.imageJar) {
        this.onExit(code);
        return;
      }
      lastExit = String(code);
      lastJar = jar;
      this.restarting = true;
      this.log.note(`release ${jar} exited with ${code}; choosing a jar`);
    }
  }

  /** One run of one jar, resolved with its exit code. */
  run(jar) {
    return new Promise((resolve) => {
      const args = [
        ...javaOptions(this.temp, []),
        '-Dorknux.update.launched=true',
        `-Dorknux.update.running-jar=${jar}`,
        `-Dorknux.update.image-jar=${this.imageJar}`,
        '-jar', jar,
        ...this.springArgs,
      ];
      const child = spawn(this.java, args, { env: this.env, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
      this.child = child;
      child.stdout.on('data', (chunk) => this.log.server(chunk));
      child.stderr.on('data', (chunk) => this.log.server(chunk));
      child.on('error', (failure) => {
        this.log.note(`could not start java: ${failure.message}`);
        this.child = null;
        resolve(-1);
      });
      child.on('exit', (code, signal) => {
        this.child = null;
        this.log.note(`the server exited with ${code ?? signal}`);
        resolve(code ?? 143);
      });
    });
  }

  /**
   * Stops the server, asking first and insisting after a grace period.
   *
   * The ask is closing the server's standard input, which under ORKNUX_DESKTOP
   * it reads to its end and then shuts down gracefully - the one stop signal
   * Windows has for a windowless process. SIGTERM is sent as well on Linux and
   * macOS, for a jar older than desktop mode; the forced kill after the grace
   * period is for a server that answers neither.
   */
  stop() {
    this.stopping = true;
    const child = this.child;
    if (child === null) return Promise.resolve();
    return new Promise((resolve) => {
      const done = () => {
        clearTimeout(force);
        resolve();
      };
      child.once('exit', done);
      const force = setTimeout(() => {
        this.log.note('the server did not stop in time; ending it');
        if (process.platform === 'win32') execFile('taskkill', ['/PID', String(child.pid), '/T', '/F'], () => {});
        else child.kill('SIGKILL');
      }, STOP_GRACE_MS);
      try {
        child.stdin?.end();
      } catch {
        // Already closed: the server is on its way out either way.
      }
      if (process.platform !== 'win32') child.kill('SIGTERM');
    });
  }
}

module.exports = { Server };
