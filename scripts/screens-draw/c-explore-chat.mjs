/**
 * What the stand-in model says for the Research and Chat chapters (#684, group C).
 *
 * Each conversation is recognised by something only it says - a research
 * step's own instruction, a question written for one picture - so no other
 * group's conversation is answered from here.
 *
 * One answer is slow on purpose: the notes on "Weekend rota notes" for the
 * weekend question, which holds a research in Gather sources long enough for
 * the manual to photograph a research while it runs. The stand-in answers one
 * request at a time, so nothing else in this lane is answered meanwhile.
 */

/** The question whose research is photographed running. */
const SLOW_QUESTION = /pro plan customers get a first reply on weekends/i;
const SLOW_SOURCE = /weekend rota notes/i;
const SLOW_SECONDS = 45;

/** Every message's text, system first, as the product sent them. */
function texts(asked, textOf) {
  return (asked.messages ?? []).map((message) => ({ role: message.role, text: textOf(message.content ?? '') }));
}

/** The question a research step names, from the user turn that carries it. */
function questionOf(said) {
  return /Question:\s*(.+)/.exec(said)?.[1]?.trim() ?? '';
}

function sleep(seconds) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, seconds * 1000);
}

/* ------------------------------------------------------------ research */

const PLANS = [
  [/missed their first-reply target/i, [
    'Northwind first-reply targets per support channel 2026',
    'Q3 first-reply times by channel against target',
    'causes of slow first replies in August and September',
  ]],
  [/pricing change in january/i, [
    'ticket volume after the last pricing change',
    'support staffing during a pricing change',
    'questions customers ask after a price rise',
  ]],
  [/help-centre articles/i, [
    'most opened help-centre articles before a ticket',
    'articles customers read before contacting support',
    'help-centre gaps that lead to tickets',
  ]],
  [/first reply on weekends/i, [
    'weekend first-reply times for Pro plan customers',
    'weekend support rota and cover',
    'Pro plan support targets',
  ]],
];

const NOTES = {
  'support sla 2026': 'Northwind\'s 2026 support targets: a first reply on chat within 2 minutes, on email within 4 business hours, and phone answered within 60 seconds. Pro plan customers have the same targets every day of the week, weekends included. A target is met when 90% of first replies in a month are inside it.',
  'q3 ticket volume review': 'Third-quarter volumes: 18,400 email tickets, 11,900 chats and 2,100 calls. Email met its 4-hour target in July (93%) and September (91%) but missed it in August (71%), when the backlog after the price-page change doubled email volume for nine days. Chat met its 2-minute target on weekdays every month (95%) and missed it on weekends (64%). Phone met its target all quarter (96%).',
  'weekend rota notes': 'Weekend cover was cut from four agents to two from July, after two people moved to the onboarding team. The median weekend first reply on chat was 9 minutes on Sundays and 6 on Saturdays. Pro plan chats are not routed ahead of others at weekends; the priority queue is switched off outside weekday hours.',
  'help centre top articles': 'The five help-centre articles opened most often in the hour before a ticket: Resetting two-factor sign-in, Exporting invoices, Changing the billing contact, Why my export failed, and Adding a seat. Two of them - Why my export failed and Resetting two-factor sign-in - end without a next step, and 38% of their readers open a ticket.',
};

function notesFor(user) {
  const source = /Source:\s*(.+)/.exec(user)?.[1]?.trim().toLowerCase().replace(/\.md$/, '') ?? '';
  const known = Object.keys(NOTES).find((name) => source.includes(name));
  return known ? NOTES[known] : 'NOTHING RELEVANT';
}

const REPORTS = [
  [/missed their first-reply target/i, (revised) => [
    '# First-reply targets in the third quarter',
    '',
    'Two of Northwind\'s three support channels missed their first-reply target for part of the third quarter: email in August, and chat at weekends throughout. Phone met its target all quarter [2].',
    '',
    '## The targets',
    '',
    'A first reply is due within 2 minutes on chat, within 4 business hours on email and within 60 seconds on the phone, and a target counts as met when 90% of a month\'s first replies fall inside it [1]. Pro plan customers have the same targets on every day of the week [1].',
    '',
    '## Email: one bad month',
    '',
    'Email met its target in July (93%) and September (91%) and fell to 71% in August [2]. The cause was a nine-day backlog after the price page changed, which doubled email volume while staffing stayed the same [2].',
    '',
    '## Chat: every weekend',
    '',
    'Chat met its target on weekdays every month, at 95%, and missed it at weekends, at 64% [2]. Weekend cover was cut from four agents to two in July, and the median Sunday first reply rose to 9 minutes [3]. ' +
      (revised ? 'Pro plan chats are not routed ahead of others at weekends, because the priority queue is switched off outside weekday hours [3].' : 'Pro plan customers are routed ahead of others.'),
    '',
    '## Conclusion',
    '',
    'August\'s email miss was a one-off with a known cause. The weekend chat miss is structural: two agents cannot meet a 2-minute target, and the Pro plan promise is broken every weekend until either the rota or the priority queue changes [3].',
  ].join('\n')],
  [/pricing change in january/i, () => [
    '# Before the January pricing change',
    '',
    'The last price-page change doubled email volume for nine days and took email to 71% against its target [2]. The same will happen in January unless the desk prepares.',
    '',
    '## What happened last time',
    '',
    'Email volume doubled for nine days in August while staffing stayed the same, and the backlog took most of the month to clear [2].',
    '',
    '## What to change',
    '',
    'Add email cover for the first two weeks, publish the new prices in the help centre before the page changes, and keep the priority queue on at weekends for Pro plan customers [1] [3].',
    '',
    '## Conclusion',
    '',
    'Two extra people on email for a fortnight would have kept August inside target [2].',
  ].join('\n')],
  [/help-centre articles/i, () => [
    '# What customers read before they ask',
    '',
    'Five help-centre articles are opened most often in the hour before a ticket, and two of them end without a next step [1].',
    '',
    '## The five articles',
    '',
    'Resetting two-factor sign-in, Exporting invoices, Changing the billing contact, Why my export failed and Adding a seat [1].',
    '',
    '## Where they fall short',
    '',
    'Why my export failed and Resetting two-factor sign-in leave the reader with nowhere to go, and 38% of their readers open a ticket [1].',
    '',
    '## Conclusion',
    '',
    'A next step at the end of those two articles is the cheapest change on the list [1].',
  ].join('\n')],
  [/first reply on weekends/i, () => [
    '# Weekend first replies for Pro plan customers',
    '',
    'Pro plan customers wait as long as anybody at weekends: the priority queue is off outside weekday hours, and the median Sunday chat first reply is 9 minutes against a 2-minute target [1] [3].',
    '',
    '## Conclusion',
    '',
    'Turning the priority queue on at weekends would honour the Pro plan target without a larger rota [3].',
  ].join('\n')],
];

const FOLLOW_UPS = {
  'missed their first-reply target': [
    'What should the support desk change before the pricing change in January?',
    'How many agents does weekend chat need to meet a 2-minute first reply?',
    'Would keeping the priority queue on at weekends slow first replies for other plans?',
  ],
};

function research(asked, said, textOf, parsed) {
  const system = said.filter((one) => one.role === 'system').map((one) => one.text).join('\n');
  const users = said.filter((one) => one.role === 'user').map((one) => one.text);
  const user = users.at(-1) ?? '';
  const question = questionOf(users.join('\n'));

  if (system.includes('You take research notes')) {
    if (SLOW_QUESTION.test(question) && SLOW_SOURCE.test(user)) sleep(SLOW_SECONDS);
    return { text: notesFor(user) };
  }
  if (system.includes('You deepen research')) return { text: '[]' };
  if (system.includes('You suggest what to research next')) {
    const key = Object.keys(FOLLOW_UPS).find((one) => user.includes(one));
    return { text: JSON.stringify(key ? FOLLOW_UPS[key] : []) };
  }
  if (system.includes('You review research reports')) {
    // The first round asks for one change, which the writer makes; a later round accepts.
    const reviewed = said.some((one) => one.role === 'assistant' && /"verdict"/.test(one.text));
    if (!reviewed && /routed ahead of others\./.test(user)) {
      return {
        text: JSON.stringify({
          verdict: 'revise',
          summary: 'One claim about Pro plan routing is not in the sources and contradicts them.',
          issues: [{
            kind: 'contradiction',
            issue: '"Pro plan customers are routed ahead of others." - source 3 says the priority queue is off at weekends.',
            fix: 'Say that Pro plan chats are not routed ahead of others at weekends, citing [3].',
          }],
        }),
      };
    }
    return { text: JSON.stringify({ verdict: 'accept', summary: 'Every claim is supported and every part of the plan is answered.', issues: [] }) };
  }
  if (/^A reviewer read your report/.test(user)) {
    const report = REPORTS.find(([pattern]) => pattern.test(users.join('\n')));
    return report ? { text: report[1](true) } : null;
  }
  if (/Write exactly \d+ search queries/.test(user)) {
    const plan = PLANS.find(([pattern]) => pattern.test(question));
    return plan ? { text: JSON.stringify(plan[1]) } : null;
  }
  if (/Sources, by number:/.test(user) && /Register:/.test(user)) {
    const report = REPORTS.find(([pattern]) => pattern.test(question));
    return report ? { text: report[1](false) } : null;
  }
  return null;
}

/* ----------------------------------------------------------------- chat */

const PLAN = [
  'Found: the legacy billing webhook is still called by two workflows - **Nightly billing export** and **Refund notice** - and by nothing else.',
  '',
  '1. Point **Nightly billing export** at the new billing connection (changes that workflow).',
  '2. Point **Refund notice** at it as well (changes that workflow).',
  '3. Run both once by hand and compare their output with last night\'s (changes nothing).',
  '4. Switch the legacy webhook off, and leave it a week before removing it (changes the connection).',
].join('\n');

const RICH = [
  'At 90% within target, a month of **18,400** emails needs **16,560** first replies inside four business hours:',
  '',
  '$$\\text{inside} = \\lceil 0.9 \\times N \\rceil, \\qquad N = 18\\,400 \\Rightarrow 16\\,560$$',
  '',
  'August managed $0.71 \\times N \\approx 13\\,064$, so it was short by about **3,500** replies.',
  '',
  'To pull August\'s first-reply times from the export yourself:',
  '',
  '```powershell',
  'Import-Csv first-replies.csv | Where-Object { $_.month -eq "2026-08" -and [int]$_.minutes -gt 240 } | Measure-Object',
  '```',
  '',
  '| Channel | Target | Q3 |',
  '| --- | --- | --- |',
  '| Email | 4 business hours | 85% |',
  '| Chat | 2 minutes | 88% |',
  '| Phone | 60 seconds | 96% |',
].join('\n');

function chat(asked, said) {
  const tools = (asked.tools ?? []).map((tool) => tool.function?.name ?? tool.name).filter(Boolean);
  const users = said.filter((one) => one.role === 'user').map((one) => one.text);
  const last = users.at(-1) ?? '';
  const answered = (asked.messages ?? []).some((message) => message.role === 'tool');

  // Suggestions while typing, and replies under an answer: the Completion model.
  const system = said.filter((one) => one.role === 'system').map((one) => one.text).join('\n');
  if (system.includes('You suggest how a message somebody is typing goes on')) {
    return { text: ' the billing export, and say the fix ships at 15:30 today?' };
  }
  if (system.includes('You suggest short replies a person might send next')) {
    const conversation = said.map((one) => one.text).join('\n');
    if (/filed it/i.test(conversation)) return { text: 'Label it p1\nAssign it to Dana\nRemind me at 15:30' };
    if (/log in|login/i.test(conversation)) return { text: 'Put it as a checklist\nWhat if two-factor is the problem?\nDraft the reply to the customer' };
    return { text: 'Make it shorter\nGo into more detail\nWhat should I do first?' };
  }

  if (/legacy billing webhook/i.test(last)) {
    if (tools.includes('present_plan') && !answered) return { call: { name: 'present_plan', arguments: { plan: PLAN } } };
    if (answered) return { text: 'The plan is with you: approve it and I will carry it out, or tell me what to look at again.' };
  }
  if (/first-reply target, in numbers/i.test(last)) return { text: RICH };
  // The seed's own login question, which the suggested replies are photographed under.
  if (/cannot log in\. what should i check first/i.test(last) && !answered) {
    return {
      text: [
        'In this order, cheapest and most likely first:',
        '',
        '1. **The status page** - an outage answers it for everybody at once.',
        '2. **The address they sign in with** - a typo or an old address is the commonest cause.',
        '3. **Whether the account is locked** after too many attempts; it unlocks itself after 15 minutes.',
        '4. **Two-factor** - a new phone without the authenticator app moved over.',
        '5. **Single sign-on** - if their company signs in through it, the fault may be on their side.',
      ].join('\n'),
    };
  }
  if (/file an issue for the billing export/i.test(last)) {
    if (!answered) {
      const issue = {
        title: 'Nightly billing export failed three nights running',
        description: 'The export has failed every night since Monday after a schema change on our side. Fix expected by 15:30 today.',
        type: 'bug',
      };
      if (tools.includes('orknux_open_issue')) return { call: { name: 'orknux_open_issue', arguments: issue } };
      if (tools.includes('tool_run')) return { call: { name: 'tool_run', arguments: { name: 'orknux_open_issue', arguments: issue } } };
    }
    if (answered) return { text: 'Filed it - the link is on the line above. I have put the failure and the expected fix in its description.' };
  }
  return null;
}

export function answer(asked, { textOf, parsed }) {
  const said = texts(asked, textOf);
  return research(asked, said, textOf, parsed) ?? chat(asked, said);
}
