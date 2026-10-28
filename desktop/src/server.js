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
/** How often the start asks whether the server answers: a refused connection costs nothing, and every tick waited is time the window stays on the splash. */
const READY_POLL_MS = 100;
const STOP_GRACE_MS = 15_000;

/**
 * The largest file an upload may be here, in MB, where the person has not said.
 * The jar and the images keep 25, sized for a server somebody else's disk is
 * under; this machine's disk is the person's own, and a video or a dataset is an
 * ordinary thing to attach. Admin Settings' Largest file overrides it.
 */
const DESKTOP_UPLOAD_MAX_MB = '500';

/** ORKNUX_ATTACHMENTS_MAX_FILE_SIZE_MB as the server is given it: the environment's when it has one, else the desktop's. */
function uploadLimitOf(env) {
  const said = env.ORKNUX_ATTACHMENTS_MAX_FILE_SIZE_MB;
  return typeof said === 'string' && said.trim() !== '' ? said : DESKTOP_UPLOAD_MAX_MB;
}

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

/** Left in the data directory while the server runs a release rather than the shipped jar. */
const RAN_RELEASE = 'ran-release';
/** ReleaseLauncher's BLIND_MARKER: written in the release directory when it could not read the release table. */
const LAUNCHER_BLIND = 'launcher-could-not-read-releases';

/**
 * Whether ReleaseLauncher has anything to decide before this start.
 *
 * Asking it is a second JVM start, a second or more before the server's own,
 * and on an ordinary start its answer is always the shipped jar. It has a
 * choice to make only after the server exited - 75 to follow a release an
 * administrator activated or rolled back, anything else to count against the
 * release that crashed - when the last start ran a release, so that one keeps
 * running, under a pin, or when it could not read the release table last time
 * and has to clear its marker. A release chosen while none of those held is
 * still reached: the server restarts itself with 75 when the release it should
 * run is not the one running (ServerReleases.started).
 *
 * @param {{ afterExit: boolean, ranRelease: boolean, blind: boolean, pinned: boolean }} state
 */
function launcherNeeded({ afterExit, ranRelease, blind, pinned }) {
  return afterExit || ranRelease || blind || pinned;
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

/**
 * How a jar is started: from where, with which `-jar`, and with what else.
 *
 * The shipped jar has an unpacked copy beside it, `app/`, and usually an AOT
 * cache in it, both made by scripts/aot.sh with this package's runtime (#650).
 * Started from inside that directory with a relative `-jar app.jar` and the
 * cache, the server is up in about half the time; the cache records the names
 * of the files it was made from, so the relative path is what lets it follow
 * the package to wherever it is installed. A cache the JVM finds does not match
 * is said in the server's log and the start goes on without it.
 *
 * A release the server installed is a jar on its own and starts as it always
 * has, as does a package built without scripts/aot.sh.
 *
 * @param {string} jar      the jar chosen to run
 * @param {string} imageJar the jar shipped with this install
 * @returns {{ cwd: string | undefined, jar: string, options: string[] }}
 */
function launchOf(jar, imageJar) {
  const unpacked = path.join(path.dirname(imageJar), 'app');
  // Resolved, because the launcher prints the jar as Java spells a path.
  if (path.resolve(jar) !== path.resolve(imageJar) || !fs.existsSync(path.join(unpacked, 'app.jar'))) {
    return { cwd: undefined, jar, options: [] };
  }
  const hasCache = fs.existsSync(path.join(unpacked, 'app.aot'));
  if (hasCache) restoreTimes(unpacked);
  const cache = hasCache ? ['-XX:AOTCache=app.aot'] : [];
  return { cwd: unpacked, jar: 'app.jar', options: cache };
}

/**
 * Puts back the times the jars had when the cache was made.
 *
 * The JVM refuses a cache when a jar it was made from has another timestamp,
 * and an installer - NSIS and the MSI alike - stamps every file with the time
 * it was installed: the cache built for #650 was refused on the first install
 * and every start took as long as before. scripts/aot.sh writes the times down
 * as `seconds<TAB>path` beside the cache. A file that cannot be touched - an
 * install somebody cannot write to - is left as it is, and the start goes on
 * without the cache, as it would have anyway.
 *
 * @param {string} unpacked the unpacked server directory
 * @returns {number} how many files had their time put back
 */
function restoreTimes(unpacked) {
  let held;
  try {
    held = fs.readFileSync(path.join(unpacked, 'aot-times.tsv'), 'utf8');
  } catch {
    return 0;
  }
  let restored = 0;
  for (const line of held.split(/\r?\n/)) {
    const tab = line.indexOf('\t');
    if (tab <= 0) continue;
    const seconds = Number(line.slice(0, tab));
    const file = path.join(unpacked, line.slice(tab + 1));
    // Inside the directory only: the list is ours, but a path is still a path.
    if (!Number.isFinite(seconds) || !path.resolve(file).startsWith(path.resolve(unpacked) + path.sep)) continue;
    try {
      if (Math.floor(fs.statSync(file).mtimeMs / 1000) === seconds) continue;
      fs.utimesSync(file, seconds, seconds);
      restored += 1;
    } catch {
      // Not writable, or gone: the JVM says so in the log and starts without the cache.
    }
  }
  return restored;
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
      // Run on a chat answer's shell blocks: the machine is the person's own here, and Windows and Linux have a prompt to ask for administrator rights with. A value already in the environment wins.
      ORKNUX_CHAT_RUN_COMMANDS: process.env.ORKNUX_CHAT_RUN_COMMANDS ?? (process.platform === 'win32' || process.platform === 'linux' ? 'true' : 'false'),
      // 500 MB rather than the jar's 25; a value already in the environment wins.
      ORKNUX_ATTACHMENTS_MAX_FILE_SIZE_MB: uploadLimitOf(process.env),
      MALLOC_ARENA_MAX: process.env.MALLOC_ARENA_MAX ?? '2',
    };
    this.apply(prepared, network);
    this.temp = prepared.temp;
    this.ranRelease = path.join(data, RAN_RELEASE);

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
      await new Promise((resolve) => setTimeout(resolve, READY_POLL_MS));
    }
    throw new Error(`The server did not answer within ${READY_TIMEOUT_MS / 1000} seconds.`);
  }

  /**
   * Which jar to run: ReleaseLauncher's answer, the last line it prints, or the
   * shipped jar where it answers nothing usable. Asked before every start, as
   * the script asks, with what the previous start ended in.
   */
  choose(lastExit, lastJar, afterExit) {
    if (!this.selfUpdate) return Promise.resolve(this.imageJar);
    const asked = launcherNeeded({
      afterExit,
      ranRelease: fs.existsSync(this.ranRelease),
      blind: fs.existsSync(path.join(this.env.ORKNUX_RELEASE_DIR, LAUNCHER_BLIND)),
      pinned: (this.env.ORKNUX_RELEASE_PIN ?? '').trim() !== '',
    });
    if (!asked) {
      this.log.note('no release to consider; running the shipped jar');
      return Promise.resolve(this.imageJar);
    }
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
    let afterExit = false;
    for (;;) {
      this.restarting = true;
      const jar = await this.choose(lastExit, lastJar, afterExit);
      this.restarting = false;
      if (this.stopping) return;
      this.noteRelease(jar !== this.imageJar);
      afterExit = true;
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

  /** Remembers, across quitting the app, whether a release is what runs. */
  noteRelease(running) {
    try {
      if (running) fs.writeFileSync(this.ranRelease, '');
      else fs.rmSync(this.ranRelease, { force: true });
    } catch (failure) {
      this.log.note(`could not note which jar runs: ${failure.message}`);
    }
  }

  /** One run of one jar, resolved with its exit code. */
  run(jar) {
    return new Promise((resolve) => {
      const launch = launchOf(jar, this.imageJar);
      const args = [
        ...javaOptions(this.temp, launch.options),
        '-Dorknux.update.launched=true',
        // The jar itself, not the unpacked copy: it is what the version and the schema floor are read from.
        `-Dorknux.update.running-jar=${jar}`,
        `-Dorknux.update.image-jar=${this.imageJar}`,
        '-jar', launch.jar,
        ...this.springArgs,
      ];
      const child = spawn(this.java, args, { cwd: launch.cwd, env: this.env, windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
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

module.exports = { Server, launchOf, launcherNeeded, restoreTimes, uploadLimitOf };
