/**
 * Group A of the manual (#684): Images, Videos and the desktop app.
 *
 * Its chapters photograph what the core's stand-in does not serve: a video
 * being made and a video made, and the image editor's Upscale, Clean up and
 * Harmonize, which go to a Stable Diffusion WebUI. So this answers two more
 * servers from the same port, each recognised by something only this group's
 * providers send, never by the path alone:
 *
 * - a Stable Diffusion WebUI under `/webui` - the WebUI provider's endpoint
 *   carries that prefix, and the product calls `<endpoint>/sdapi/v1/...`;
 * - a stable-diffusion.cpp server for the host `127.0.0.1`. Its paths are the
 *   host's root (`/sdcpp/v1/...`) whatever the endpoint says, so the host the
 *   provider was given is what tells it apart: the core seed reaches this
 *   machine as `localhost`, and answering its `/sdcpp/v1/capabilities` would
 *   save the Writer's provider as an image server that takes no tools (#670).
 *
 * The videos made are `a-design-media/*.webm`, four seconds each panned across
 * a scene this stand-in painted, made once with FFmpeg and picked by the prompt; a video asked for
 * with "first boats go out" in its prompt is kept generating, a step at a time, so the
 * Videos page has one on the way to photograph. A picture asked for with
 * "lighthouse" in its prompt is held for three minutes, for the same reason
 * on the Images page; the shot after it stops it.
 */
import { readFileSync } from 'node:fs';

const VIDEOS = Object.fromEntries(
  ['harbour', 'lake', 'mist'].map((name) => [name, readFileSync(new URL(`./a-design-media/${name}.webm`, import.meta.url)).toString('base64')]),
);
/** Which of the three a prompt gets: a lake, mist over mountains, or the harbour. */
const videoFor = (prompt) => (/lake/i.test(prompt) ? VIDEOS.lake : /mist|mountain/i.test(prompt) ? VIDEOS.mist : VIDEOS.harbour);

/** The WebUI's algorithms, as an AUTOMATIC1111 with a few models added lists them. */
const UPSCALERS = ['None', 'Lanczos', 'Nearest', 'R-ESRGAN 4x+', '4x-UltraSharp', 'ESRGAN_4x', 'SwinIR_4x', 'ScuNET GAN', 'ScuNET PSNR']
  .map((name) => ({ name, model_name: name === 'None' ? null : name, scale: 4 }));

const jobs = new Map();
let made = 0;

const hostOf = (request) => String(request.headers.host ?? '').split(':')[0];
const json = (body) => { try { return JSON.parse(body.toString('utf8') || '{}'); } catch { return {}; } };

/** No chat of this group's is scripted: every conversation is left to the others. */
export function answer() {
  return null;
}

export async function serve({ request, response, path, body, answer, draw }) {
  /* ---- a Stable Diffusion WebUI, under /webui ---- */
  if (path.startsWith('/webui/')) {
    const api = path.slice('/webui'.length);
    if (request.method === 'GET' && api === '/sdapi/v1/upscalers') return answer(response, 200, UPSCALERS), true;
    if (request.method === 'GET' && api === '/sdapi/v1/sd-models') {
      return answer(response, 200, [{ title: 'sdxl_base_1.0.safetensors [31e35c80fc]', model_name: 'sdxl_base_1.0', filename: 'sdxl_base_1.0.safetensors' }]), true;
    }
    if (request.method === 'GET' && api === '/sdapi/v1/options') return answer(response, 200, { sd_model_checkpoint: 'sdxl_base_1.0.safetensors [31e35c80fc]' }), true;
    if (request.method === 'GET' && api.startsWith('/sdapi/v1/progress')) return answer(response, 200, { progress: 0, eta_relative: 0, state: { job_count: 0 } }), true;
    if (request.method === 'POST' && api === '/sdapi/v1/interrupt') return answer(response, 200, {}), true;
    // What comes back is what went in: the picture is the same, the page goes on as it would.
    if (request.method === 'POST' && api === '/sdapi/v1/extra-single-image') return answer(response, 200, { image: json(body).image ?? '' }), true;
    if (request.method === 'POST' && api === '/sdapi/v1/extra-batch-images') {
      return answer(response, 200, { images: (json(body).imageList ?? []).map((one) => one.data) }), true;
    }
    if (request.method === 'POST' && api === '/sdapi/v1/img2img') return answer(response, 200, { images: [(json(body).init_images ?? [''])[0]] }), true;
    return answer(response, 404, { detail: 'Not Found' }), true;
  }

  if (hostOf(request) !== '127.0.0.1') {
    /* ---- the core's drawing, except the one picture held on the way ---- */
    if (request.method === 'POST' && path.endsWith('/images/generations') && /lighthouse/i.test(String(json(body).prompt ?? ''))) {
      const asked = json(body);
      const until = Date.now() + 180_000;
      // The socket, not the request: a request reads as destroyed as soon as its body is in.
      while (Date.now() < until && !request.socket.destroyed) await new Promise((done) => setTimeout(done, 1000));
      if (request.socket.destroyed) return true;
      answer(response, 200, { created: Math.floor(Date.now() / 1000), data: [{ b64_json: draw(asked.prompt, asked.size).toString('base64') }] });
      return true;
    }
    return false;
  }

  /* ---- a stable-diffusion.cpp server, for 127.0.0.1 only ---- */
  if (request.method === 'GET' && path === '/sdcpp/v1/capabilities') {
    answer(response, 200, {
      supported_modes: ['img_gen', 'vid_gen'],
      defaults_by_mode: {
        img_gen: { width: 1024, height: 1024, seed: -1 },
        vid_gen: { video_frames: 33, fps: 16, width: 832, height: 480, seed: -1 },
      },
      features_by_mode: { img_gen: { negative_prompt: true }, vid_gen: { init_image: true, negative_prompt: true } },
      limits: { min_width: 64, max_width: 1280, min_height: 64, max_height: 1280 },
      output_formats_by_mode: { img_gen: ['png'], vid_gen: ['webm', 'avi'] },
      preview_modes: ['proj'],
    });
    return true;
  }
  if (request.method === 'POST' && path === '/sdcpp/v1/vid_gen') {
    const asked = json(body);
    made += 1;
    const id = `vid-${made}`;
    jobs.set(id, { prompt: String(asked.prompt ?? ''), polls: 0, held: /first boats go out/i.test(String(asked.prompt ?? '')), cancelled: false });
    console.log(`video ${id} asked for: ${String(asked.prompt ?? '').slice(0, 100)}`);
    answer(response, 200, { id, status: 'queued' });
    return true;
  }
  const job = /^\/sdcpp\/v1\/jobs\/([^/]+)(\/cancel)?$/.exec(path);
  if (job) {
    const held = jobs.get(job[1]);
    if (held === undefined) return answer(response, 404, { error: { message: 'No such job' } }), true;
    if (job[2]) {
      held.cancelled = true;
      return answer(response, 200, { id: job[1], status: 'cancelled' }), true;
    }
    if (held.cancelled) return answer(response, 200, { id: job[1], status: 'cancelled' }), true;
    held.polls += 1;
    // A video kept on the way creeps forward a step a poll and never quite gets there.
    if (held.held) {
      const step = Math.min(29, 3 + held.polls);
      return answer(response, 200, { id: job[1], status: 'generating', preview: { step, total_steps: 30 } }), true;
    }
    if (held.polls < 3) return answer(response, 200, { id: job[1], status: 'generating', preview: { step: held.polls * 10, total_steps: 30 } }), true;
    answer(response, 200, {
      id: job[1],
      status: 'completed',
      result: { b64_json: videoFor(held.prompt), mime_type: 'video/webm', fps: 16, frame_count: 64 },
    });
    return true;
  }
  return false;
}
