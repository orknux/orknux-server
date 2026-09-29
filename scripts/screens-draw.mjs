/**
 * The drawing model the manual's installation draws with.
 *
 * The chapters that describe drawing - a picture asked for in a chat, a task
 * that drew, an image node in a workflow - had nothing to show, because the
 * installation screenshots.ps1 builds has no image model and must not reach the
 * network for one. Inserting pictures behind the product's back would photograph
 * rows no real drawing ever wrote, so this stands in for what is missing:
 * something answering OpenAI's image API, and - further down, with the reason -
 * a scripted chat model for the one agent that asks for pictures. The chat, the
 * task tool and the image node all go down their real paths to reach it, and
 * file what comes back exactly as they would file a real model's picture.
 * Issue #576.
 *
 * What it draws is a landscape, rendered here pixel by pixel from the words in
 * the description: dawn, day, dusk or night for the sky; a harbour, a lake or
 * the sea for water, with the land reflected in it and boats on it; mountains
 * or hills behind. Not a blank square, because a picture of a blank square
 * teaches a reader that drawing does not work - and not a bundled photograph,
 * because a different description ought to come back as a different picture.
 *
 * Run by scripts/screens-compose.yaml as the `draw` service; nothing else needs
 * it. By hand, from a container with Node in it:
 *
 *   node scripts/screens-draw.mjs 8299
 */
import { createServer } from 'node:http';
import { crc32, deflateSync } from 'node:zlib';

const PORT = Number(process.argv[2] ?? process.env.PORT ?? 8299);

/* ------------------------------------------------------------- the painting */

const clamp = (value, low = 0, high = 1) => Math.min(high, Math.max(low, value));
const mix = (a, b, t) => [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t];

/** The same description draws the same picture, so a retake does not move. */
function seeded(text) {
  let h = 2166136261;
  for (const char of text) {
    h ^= char.codePointAt(0);
    h = Math.imul(h, 16777619);
  }
  let state = h >>> 0 || 1;
  return () => {
    state ^= state << 13;
    state ^= state >>> 17;
    state ^= state << 5;
    return (state >>> 0) / 4294967296;
  };
}

/** Sky from top to horizon, the light, the land from far to near, the water from horizon down. */
const MOODS = {
  dawn: {
    sky: [[62, 78, 140], [250, 184, 146]],
    light: [255, 236, 196],
    glow: [255, 196, 156],
    land: [[150, 120, 160], [46, 44, 78]],
    water: [[244, 186, 160], [58, 70, 116]],
    lightAt: 'horizon',
  },
  day: {
    sky: [[64, 132, 212], [196, 228, 246]],
    light: [255, 252, 232],
    glow: [255, 255, 236],
    land: [[140, 180, 176], [44, 96, 74]],
    water: [[160, 206, 228], [36, 88, 140]],
    lightAt: 'high',
  },
  dusk: {
    sky: [[44, 32, 88], [252, 146, 92]],
    light: [255, 210, 130],
    glow: [255, 150, 92],
    land: [[124, 80, 120], [30, 20, 48]],
    water: [[236, 142, 104], [38, 28, 70]],
    lightAt: 'horizon',
  },
  night: {
    sky: [[8, 12, 36], [42, 58, 108]],
    light: [236, 236, 222],
    glow: [140, 164, 222],
    land: [[48, 58, 96], [10, 12, 26]],
    water: [[46, 62, 110], [6, 8, 22]],
    lightAt: 'high',
    stars: true,
  },
};

function moodOf(words) {
  if (/night|midnight|star|moon/.test(words)) return MOODS.night;
  if (/dusk|sunset|evening|twilight/.test(words)) return MOODS.dusk;
  if (/dawn|sunrise|morning|daybreak/.test(words)) return MOODS.dawn;
  return MOODS.day;
}

/**
 * One landscape, as rows of RGB.
 *
 * Every edge is given partial coverage rather than a hard step - a ridge line
 * drawn on whole pixels is a staircase, and a staircase reads as a program
 * rather than as a picture.
 */
function paint(description, width, height) {
  const words = description.toLowerCase();
  const random = seeded(words);
  const mood = moodOf(words);
  const water = /harbou?r|sea\b|seaside|lake|water|coast|bay\b|river|ocean|port\b|boat|sail/.test(words);
  const mountains = /mountain|peak|alp|summit|ridge/.test(words);
  const boats = water && /harbou?r|boat|sail|port\b|sea\b|bay\b/.test(words);

  const W = width;
  const H = height;
  const horizon = water ? H * 0.64 : H * 0.78;

  const lightX = W * (0.22 + 0.56 * random());
  const high = H * (0.16 + 0.08 * random());
  const radius = H * (mood.stars ? 0.045 : 0.065);

  /*
   * The ridges, one height per column per layer, far to near. Hills are a few
   * slow waves added together; mountains add a triangle wave for the peaks.
   */
  const LAYERS = 4;
  const ridges = [];
  for (let layer = 0; layer < LAYERS; layer += 1) {
    const depth = layer / (LAYERS - 1);
    const base = water
      ? horizon - H * (0.2 - 0.045 * layer) * (mountains ? 1.15 : 0.7)
      : horizon - H * (0.34 - 0.1 * layer) + (layer === LAYERS - 1 ? H * 0.12 : 0);
    const waves = Array.from({ length: 3 }, (_, k) => ({
      amplitude: H * (mountains ? 0.035 : 0.028) / (k + 1) * (1 + depth * 0.4),
      frequency: ((1.2 + random() * 1.6) * (k + 1) * Math.PI * 2) / W,
      phase: random() * Math.PI * 2,
    }));
    const peak = mountains
      ? { amplitude: H * (0.13 - 0.02 * layer), frequency: (2 + random() * 2.5) / W, phase: random() }
      : null;
    const row = new Float32Array(W);
    for (let x = 0; x < W; x += 1) {
      let y = base;
      for (const wave of waves) y -= wave.amplitude * Math.sin(x * wave.frequency + wave.phase);
      if (peak) {
        const f = (x * peak.frequency + peak.phase) % 1;
        y -= peak.amplitude * (1 - Math.abs(2 * f - 1)) - peak.amplitude * 0.5;
      }
      row[x] = y;
    }
    ridges.push(row);
  }

  /*
   * A low sun sits on the farthest ridge, half set behind it: placed before the
   * ridges were known it was hidden behind them, and a dawn with no sun in it
   * reads as a grey afternoon.
   */
  const lightY =
    mood.lightAt === 'horizon' ? ridges[0][Math.min(W - 1, Math.round(lightX))] - radius * 0.35 : high;

  const landColour = (layer, y, x) => {
    const depth = layer / (LAYERS - 1);
    const own = mix(mood.land[0], mood.land[1], depth);
    // Distance is haze: the far ridges are half sky.
    const hazed = mix(own, mood.sky[1], (1 - depth) * 0.45);
    // And a ridge darkens a little towards its foot.
    const below = clamp((y - ridges[layer][x]) / (H * 0.4));
    return mix(hazed, mood.land[1], below * 0.18);
  };

  const stars = [];
  if (mood.stars) {
    for (let i = 0; i < 180; i += 1) {
      stars.push({ x: Math.floor(random() * W), y: Math.floor(random() * horizon * 0.8), shine: 0.35 + random() * 0.65 });
    }
  }
  const starAt = new Map(stars.map((star) => [star.y * W + star.x, star.shine]));

  /* Boats: a hull and a sail each, small, near the horizon where the light is. */
  const fleet = boats
    ? Array.from({ length: 3 }, (_, i) => {
        const size = H * (0.03 + 0.015 * i + random() * 0.01);
        return { x: W * (0.12 + 0.3 * i + random() * 0.12), y: horizon + H * (0.04 + 0.06 * i), size };
      })
    : [];
  const inBoat = (x, y) =>
    fleet.some(({ x: bx, y: by, size }) => {
      // The hull: a flat trapezium sitting on the waterline.
      const hull = y <= by && y >= by - size * 0.25 && Math.abs(x - bx) <= size * (0.8 - (by - y) / size);
      // The sail: a triangle standing on the hull.
      const up = by - size * 0.25 - y;
      const sail = up >= 0 && up <= size * 1.3 && x >= bx - size * 0.05 && x - bx <= size * 0.7 * (1 - up / (size * 1.3));
      return hull || sail;
    });

  const rows = [];
  for (let y = 0; y < H; y += 1) {
    const row = Buffer.alloc(1 + W * 3);
    for (let x = 0; x < W; x += 1) {
      /* The sky, and what is in it. */
      const up = clamp(y / horizon);
      let colour = mix(mood.sky[0], mood.sky[1], up ** 0.85);
      const distance = Math.hypot(x - lightX, y - lightY);
      const glow = Math.exp(-((distance / (H * 0.36)) ** 2)) * 0.55;
      colour = mix(colour, mood.glow, glow);
      const shine = starAt.get(y * W + x);
      if (shine) colour = mix(colour, [255, 255, 245], shine);
      colour = mix(colour, mood.light, clamp(radius - distance + 0.5));

      /* The land, far to near. */
      for (let layer = 0; layer < LAYERS; layer += 1) {
        let cover = clamp(y - ridges[layer][x] + 0.5);
        if (water) cover *= clamp(horizon - y + 0.5);
        if (cover > 0) colour = mix(colour, landColour(layer, y, x), cover);
      }

      /* The water, with the land and the light in it. */
      if (water && y > horizon - 0.5) {
        const down = clamp((y - horizon) / (H - horizon));
        let sea = mix(mood.water[0], mood.water[1], down ** 0.8);
        const mirrored = horizon - (y - horizon) * 1.1;
        for (let layer = 0; layer < LAYERS; layer += 1) {
          if (mirrored >= ridges[layer][x]) sea = mix(sea, landColour(layer, mirrored, x), 0.4 - 0.25 * down);
        }
        // A broken column of light under the sun or the moon.
        const spread = radius * (1.3 + down * 3);
        const across = Math.abs(x - lightX) / spread;
        if (across < 1) {
          const ripple = Math.sin(y * 0.8 + Math.sin(x * 0.05) * 2) > 0.2 ? 1 : 0.25;
          sea = mix(sea, mood.glow, (1 - across) * (1 - down) * 0.7 * ripple);
        }
        colour = mix(colour, sea, clamp(y - horizon + 0.5));
        if (inBoat(x, y)) colour = mix(colour, mood.land[1], 0.92);
      }

      /* A little vignette, and a little grain so the gradients do not band. */
      const cx = x / W - 0.5;
      const cy = y / H - 0.5;
      const vignette = 1 - 0.28 * (cx * cx + cy * cy);
      const grain = (random() - 0.5) * 2.4;
      const at = 1 + x * 3;
      row[at] = clamp(colour[0] * vignette + grain, 0, 255);
      row[at + 1] = clamp(colour[1] * vignette + grain, 0, 255);
      row[at + 2] = clamp(colour[2] * vignette + grain, 0, 255);
    }
    rows.push(row);
  }
  return Buffer.concat(rows);
}

/** Rows of RGB, each led by filter byte 0, as a PNG. */
function png(width, height, raw) {
  const chunk = (kind, payload) => {
    const body = Buffer.concat([Buffer.from(kind, 'ascii'), payload]);
    const length = Buffer.alloc(4);
    length.writeUInt32BE(payload.length);
    const check = Buffer.alloc(4);
    check.writeUInt32BE(crc32(body));
    return Buffer.concat([length, body, check]);
  };
  const header = Buffer.alloc(13);
  header.writeUInt32BE(width, 0);
  header.writeUInt32BE(height, 4);
  header.set([8, 2, 0, 0, 0], 8);
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', header),
    chunk('IDAT', deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

/**
 * The size asked for, within reason.
 *
 * A drawing tool may ask for anything its model's parameters allow - up to 4096
 * a side - and a picture of that size rendered in JavaScript is a request that
 * times out. The picture only has to be larger than the card it is shown in.
 */
function sizeOf(asked) {
  const match = /^(\d+)x(\d+)$/.exec(String(asked ?? ''));
  if (!match) return [1024, 768];
  const [w, h] = [Number(match[1]), Number(match[2])];
  const scale = Math.min(1, 1600 / Math.max(w, h));
  return [Math.max(64, Math.round(w * scale)), Math.max(64, Math.round(h * scale))];
}

export function draw(description, size) {
  const [width, height] = sizeOf(size);
  return png(width, height, paint(description, width, height));
}

/* ------------------------------------------------------------- the writer */

/*
 * And the agent that asks for the drawing, scripted.
 *
 * The first version of this left the asking to the local model the rest of the
 * manual is photographed with, and it did not draw: offered the tool, it wrote
 * that it was "a text-based AI" and could not; told the tool's name, it called
 * it with `prompt` where the tool takes `description`, was refused, and forgot
 * what it had been asked. A picture of any of that is a picture of drawing not
 * working, and whether it happens is down to a 4B model's afternoon. So the
 * agent that draws answers from here, deterministically, and everything past
 * its tool call - the product's own tool, the image model, the storage, the
 * thread, the task's outcome - is the product doing what it does.
 *
 * What it does: every sentence of the request with a colon in it names a
 * picture, the words after the colon being the scene. It draws each one with
 * whichever drawing tool it was offered, one call per turn, then says what it
 * drew - or, in a task, calls `task_done` with that.
 */
const textOf = (content) =>
  typeof content === 'string'
    ? content
    : Array.isArray(content)
      ? content.map((part) => (typeof part === 'string' ? part : part?.text ?? '')).join('\n')
      : '';

function scenesIn(request) {
  return request
    .split(/\n+|(?<=\.)\s+/)
    .map((line) => line.trim())
    .filter((line) => line.includes(':'))
    .map((line) => ({ label: line.slice(0, line.indexOf(':')).trim(), scene: line.slice(line.indexOf(':') + 1).trim() }))
    .filter((one) => one.scene.length > 8);
}

function write(asked) {
  const messages = Array.isArray(asked.messages) ? asked.messages : [];
  const tools = (asked.tools ?? []).map((tool) => tool.function?.name ?? tool.name).filter(Boolean);
  const drawTool = tools.find((name) => name.endsWith('draw_picture'));
  const finishTool = tools.find((name) => name === 'task_done');

  const requests = messages.filter((message) => message.role === 'user').map((message) => textOf(message.content));
  const scenes = requests.map(scenesIn).find((found) => found.length > 0) ?? [];

  const drawn = new Set();
  for (const message of messages) {
    for (const call of message.tool_calls ?? []) {
      if (!call.function?.name?.endsWith('draw_picture')) continue;
      try {
        drawn.add(JSON.parse(call.function.arguments).description);
      } catch {
        // Not one of ours; it names nothing to skip.
      }
    }
  }

  const next = drawTool ? scenes.find((one) => !drawn.has(one.scene)) : undefined;
  if (next) return { call: { name: drawTool, arguments: { description: next.scene } } };

  const said =
    scenes.length === 0
      ? 'There is nothing here I can draw - say what the picture should be of, after a colon.'
      : scenes.length === 1
        ? `Here it is: ${scenes[0].scene}`
        : `Drew ${scenes.length} pictures. ${scenes.map((one) => `${one.label}: ${one.scene}`).join(' ')}`;
  if (finishTool) return { call: { name: finishTool, arguments: { summary: said } } };
  return { text: said };
}

function completion(asked) {
  const answer = write(asked);
  const message = answer.call
    ? {
        role: 'assistant',
        content: null,
        tool_calls: [
          {
            id: `call_${Date.now().toString(36)}`,
            type: 'function',
            function: { name: answer.call.name, arguments: JSON.stringify(answer.call.arguments) },
          },
        ],
      }
    : { role: 'assistant', content: answer.text };
  return {
    id: `chatcmpl-${Date.now().toString(36)}`,
    object: 'chat.completion',
    created: Math.floor(Date.now() / 1000),
    model: String(asked.model ?? 'writer'),
    choices: [{ index: 0, message, finish_reason: answer.call ? 'tool_calls' : 'stop' }],
    usage: { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0 },
  };
}

/* ----------------------------------------------------------------- the API */

const answer = (response, status, body) => {
  const payload = Buffer.from(JSON.stringify(body));
  response.writeHead(status, { 'content-type': 'application/json', 'content-length': payload.length });
  response.end(payload);
};

if (process.argv[1]?.endsWith('screens-draw.mjs')) {
  createServer((request, response) => {
    const parts = [];
    request.on('data', (part) => parts.push(part));
    request.on('end', () => {
      // A provider check lists models; answering one keeps the provider green.
      if (request.method === 'GET') {
        answer(response, 200, {
          object: 'list',
          data: [
            { id: 'illustrator', object: 'model' },
            { id: 'writer', object: 'model' },
          ],
        });
        return;
      }
      const path = request.url.split('?')[0];
      let asked;
      try {
        asked = JSON.parse(Buffer.concat(parts).toString('utf8') || '{}');
      } catch {
        answer(response, 400, { error: { message: 'The body is not JSON.' } });
        return;
      }
      /*
       * Plain JSON even where a stream was asked for: the product asks again
       * without streaming when a stream carries nothing, so an ordinary body is
       * an answer it reads.
       */
      if (request.method === 'POST' && path.endsWith('/chat/completions')) {
        const written = completion(asked);
        const said = written.choices[0].message;
        console.log(`wrote ${said.tool_calls ? `a call to ${said.tool_calls[0].function.name} ${said.tool_calls[0].function.arguments}` : JSON.stringify(said.content)}`);
        answer(response, 200, written);
        return;
      }
      if (request.method !== 'POST' || !path.endsWith('/images/generations')) {
        answer(response, 404, { error: { message: 'Only /images/generations and /chat/completions answer here.' } });
        return;
      }
      const prompt = String(asked.prompt ?? '').trim();
      if (prompt === '') {
        answer(response, 400, { error: { message: 'There is nothing to draw.' } });
        return;
      }
      const picture = draw(prompt, asked.size);
      console.log(`drew ${asked.size ?? 'the default size'} (${picture.length} bytes): ${prompt.slice(0, 120)}`);
      answer(response, 200, {
        created: Math.floor(Date.now() / 1000),
        data: [{ b64_json: picture.toString('base64'), revised_prompt: prompt }],
      });
    });
  }).listen(PORT, '0.0.0.0', () => console.log(`drawing on ${PORT}`));
}
