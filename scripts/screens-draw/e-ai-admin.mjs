/**
 * What the stand-in says in the conversations the models, administration and
 * reports chapters photograph (#684, group E), matched word for word on the
 * questions orknux-ui/scripts/seed/e-ai-admin.mjs asks.
 *
 * Each answer carries what it cost, because those chapters' pictures are of
 * numbers - tokens by person, a day's usage, what a conversation came to - and
 * the night shift's carry llama.cpp's timings too, which is what a model's
 * speed is read from. The numbers are what a real model of that size would
 * report for a question of that length, give or take.
 */

/** The newest thing a person said. */
const lastAsked = (messages, textOf) =>
  textOf([...messages].reverse().find((message) => message.role === 'user')?.content ?? '');

const SAYS = [
  {
    asks: 'How many tickets did Contoso open this week',
    text:
      'Contoso opened 14 tickets this week. 11 are closed; 3 are waiting on us:\n\n' +
      '- #4412 export file missing the VAT column (since Tuesday)\n' +
      '- #4420 a login loop on the mobile app\n' +
      '- #4431 a question about the October invoice\n\n' +
      'The export one is the oldest; the other two came in today.',
    usage: [6120, 214, 4096],
  },
  {
    asks: 'Draft a reply to Fabrikam about the delayed nightly export',
    text:
      'Hi Megan,\n\nI am sorry - last night\'s export to you ran four hours late. The mail relay it waits on was ' +
      'restarted at 18:40 and the job did not pick up again until it was rerun at 23:10. Tonight\'s ran on time, and we ' +
      'have added a check so a late start pages us rather than waiting for the morning.\n\nBest,\nAlice',
    usage: [5480, 168, 4096],
  },
  {
    asks: 'five stars, I sent it without changing a word',
    call: { name: 'rate_session', arguments: { score: 5, review: 'Exactly right - sent without changing a word.' } },
    after: 'Thank you - your review is saved with this conversation.',
    usage: [5710, 61, 5120],
  },
  {
    asks: 'Summarise yesterday\'s escalations for the morning stand-up',
    text:
      'Yesterday: 4 escalations.\n\n' +
      '1. **Northwind Traders** - nightly export late; rerun at 23:10, delivered.\n' +
      '2. **Contoso** - VAT column missing from the export; fix in review.\n' +
      '3. **Fabrikam** - SSO login loop for two users; workaround sent.\n' +
      '4. **Litware** - renewal invoice disputed; with accounts.',
    usage: [3920, 142, 0],
    fast: true,
  },
  {
    asks: 'it missed the Tailspin ticket. Four stars',
    call: { name: 'rate_session', arguments: { score: 4, review: 'Good summary, but it missed the Tailspin ticket.' } },
    after: 'Noted, and sorry about Tailspin - it was escalated after midnight, which is why the summary left it out.',
    usage: [4180, 58, 3584],
    fast: true,
  },
  {
    asks: 'Send the renewal reminder for Litware',
    call: {
      name: 'ask_person',
      arguments: {
        question: 'Litware is on the legacy Team plan. Which plan should the renewal reminder quote?',
        options: [
          { label: 'Team (legacy)', description: 'Renew as they are: same price, the plan is retired next year.' },
          { label: 'Business', description: 'The plan Team moves to: 12% more, adds SSO and the export API.' },
          { label: 'Both', description: 'Quote both and let them choose.' },
        ],
      },
    },
    after: 'I have asked which plan to quote; the reminder goes out as soon as you choose.',
    usage: [5230, 96, 4096],
  },
  {
    asks: 'Remind me every weekday at nine to look at the export queue',
    call: {
      name: 'schedule_set',
      arguments: { note: 'Look at the export queue', cron: '0 9 * * 1-5', timezone: 'Europe/Warsaw' },
    },
    after: 'Done - every weekday at 09:00 (Warsaw) I will remind you to look at the export queue. It is on the Schedules page if you want to stop it.',
    usage: [3810, 74, 2048],
    fast: true,
  },
  {
    asks: 'Quote Wingtip the upgrade to the Business plan for 40 seats',
    text:
      'Wingtip, 40 seats on Business: **$1,960 a month** ($49 a seat), billed annually at $23,520. ' +
      'Their current Team plan is $1,480 a month, so the upgrade adds $480 a month.',
    usage: [5040, 71, 4096],
  },
  {
    asks: 'it quoted the legacy price list',
    call: { name: 'rate_session', arguments: { score: 2, review: 'Quoted the legacy price list, not this year\'s.' } },
    after: 'Sorry - I read the 2025 list. This year Business is $54 a seat: $2,160 a month for 40 seats.',
    usage: [5160, 64, 4608],
  },
  {
    asks: 'Check the Slack escalations channel every two hours',
    call: { name: 'schedule_set', arguments: { note: 'Check the Slack escalations channel for anything waiting', every_seconds: 7200 } },
    after: 'Done - every two hours I will look at the escalations channel and tell you if anything is waiting.',
    usage: [3690, 58, 2048],
    fast: true,
  },
  {
    asks: 'how many tickets are waiting, and how old is the oldest',
    text: 'Waiting: 6. The oldest is #4402, the mail relay retries, opened 19 hours ago.',
    usage: [2980, 31, 2048],
    fast: true,
  },
  {
    asks: 'Write the handover for tonight',
    text:
      '**Handover - tonight**\n\n' +
      'Open: 6 tickets.\n\n' +
      '- Waiting on us: #4412 Contoso (export VAT column), #4433 Tailspin (escalated 00:20)\n' +
      '- Waiting on the customer: #4420, #4429, #4431\n' +
      '- Waiting on engineering: #4402 (mail relay retries)\n\n' +
      'Nobody is waiting longer than a day.',
    usage: [4460, 188, 0],
    fast: true,
  },
];

/** What a call of that size cost, and - on the GPU box - how fast it went. */
function cost(said, fast) {
  const [prompt, completion, cached] = said.usage;
  const usage = {
    prompt_tokens: prompt,
    completion_tokens: completion,
    total_tokens: prompt + completion,
    prompt_tokens_details: { cached_tokens: cached },
  };
  if (!fast) return { usage };
  // llama.cpp's own report: a 32B model on one GPU reads fast and writes slowly.
  const promptSpeed = 1180 + Math.round(Math.random() * 260);
  const writeSpeed = 31 + Math.round(Math.random() * 9 * 10) / 10;
  return {
    usage,
    timings: {
      prompt_n: prompt - cached,
      prompt_ms: Math.round(((prompt - cached) / promptSpeed) * 1000),
      prompt_per_second: promptSpeed,
      predicted_n: completion,
      predicted_ms: Math.round((completion / writeSpeed) * 1000),
      predicted_per_second: writeSpeed,
      cache_n: cached,
    },
  };
}

export function answer(asked, { textOf }) {
  const messages = Array.isArray(asked.messages) ? asked.messages : [];
  const question = lastAsked(messages, textOf);
  const said = SAYS.find((one) => question.includes(one.asks));
  if (!said) return null;
  const fast = said.fast === true;
  const answered = messages.at(-1)?.role === 'tool';
  if (said.call && !answered) return { call: said.call, ...cost(said, fast) };
  return { text: said.call ? said.after : said.text, ...cost(said, fast) };
}
