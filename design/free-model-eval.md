# Free agents for background jobs: test results (29 Oct 2026)

Goal: move Agentic OS's background jobs off Claude so Jhon's Claude Pro limits stay free for his own chats.
Every test used the real prompt templates from `server.py` / `jobs/`, filled with **made-up inputs only**: a fake
person (Sam Rivera), 3 fake job posts, a synthetic audit/server log, and a made-up debate. The only real input was
the ticket-plan test, which ran on a real small bug in the agentic-os code. No Claude was used. No OS files were
changed apart from this report.

The test scripts and every raw output are in
`D:\Temp\claude\D--Work-Claude\22c6b9ab-02f4-4e7e-9d30-416e4c8dd7a0\scratchpad\fme\` (`run.py` copies
`_execute_agent`'s calls, and `grade.py` runs the format checks).

## Read this first: four things that matter more than which model wins

1. **The free pool is small, and the tests emptied it today.**
   - **OpenRouter's free tier allows 50 requests a day in total** (`is_free_tier: true`, no credits on the key).
     Hermes uses OpenRouter, and so does OpenCode's `openrouter/...` models.
   - **Each Hermes run costs 2 requests**, because it makes an extra call to generate a session title.
   - These tests used up today's 50. **Hermes background jobs will get HTTP 429 until 17:00 on the PC clock.**
   - **OpenCode Zen was already at its free limit before the tests started.** Every `opencode/*-free` model returned
     `provider.free-limit` with retry-after ≈ 4 h (about 17:30 on the PC clock). All Zen free models share one limit.
   - Adding $10 of OpenRouter credit raises the free cap to 1000 requests a day. That spends money, so it's Jhon's call.
2. **Bug: `clean_hermes_output()` cuts every Hermes answer to its last 5 lines.**
   - `hermes -z` prints only the answer, with no box around it, so the function always falls back to
     `non_meta[-5:]`.
   - What it broke in these tests:
     - Nemotron Ultra's self-review JSON was pretty-printed, got cut, and failed to parse.
     - Both application drafts came through headless: the greeting and first paragraph were gone.
   - Short verdicts and one-line JSON only get through by luck.
   - Fix (not done, this was a no-code task): in `_execute_agent`'s hermes branch, return `out.strip()` for `-z`
     output.
   - **Hermes isn't safe for any job until this is fixed.**
3. **Hermes has no file tools**, because server.py gives it only `web,todo,memory,session_search`.
   - Sending `ticket_plan` to Hermes, which is what `BACKGROUND_DEFAULT = "hermes"` does today, gets no plan at all,
     just 4 `QUESTION:` lines asking Jhon where the code is.
   - Self-review on Hermes works only because the free-agent path pastes the logs into the prompt.
   - Even then it can't check the code, so the "cause" it gives is a guess. In this test the guess was wrong: it
     said the parser can't strip ``` fences, but `first_json` already handles them.
4. **Codex is not free.**
   - It's logged in with ChatGPT (`auth_mode: chatgpt`, plan **plus**), so it uses Jhon's ChatGPT Plus limits.
   - `~/.codex/config.toml` sets `service_tier = "priority"`, which burns those limits faster.
   - One ticket plan cost 48k tokens.
   - The upside: it's a different pool from Claude's, so it's fine as a paid backup, not a default.
   - Side note: in `exec` mode a Codex Stop hook fails ("hook: Stop Failed"). The run still finished.

## Results per job

Quality is scored 1-5. Time is wall-clock seconds. "Calls" means OpenRouter requests, counted from Hermes's
`--usage-file`.

### 1. self_review (logs pasted into the prompt, JSON schema check, 3 problems planted, 1 already ticketed)

| Agent | Model | Format OK | Quality | Time | Notes |
|---|---|---|---|---|---|
| Hermes | nvidia/nemotron-3.5-super-vl:free | yes (one-line JSON) | 4 | 62 s | Found all 3 planted problems and skipped the one already ticketed. Causes are guesses, since it can't read code. 2 calls. |
| Hermes | nvidia/nemotron-3-ultra-550b-a55b:free | **no**, as the OS receives it | 4 (raw) | 209 s | Raw answer was right (same 3 findings), but pretty-printed. The 5-line cut left `"category": "bug", ... }]}`. Slow. |
| OpenCode | openrouter/nvidia/nemotron-3.5-super-vl:free | yes (`first_json` finds it after its narration) | 4 | 271 s | Same 3 findings. It also went off and read the **real** audit.log/tickets.json under READ_ROOT, which used many calls and sent real OS logs to a free model. |
| Hermes | poolside/laguna-s-2.1, thinkingmachines/inkling | not tested | - | - | OpenRouter daily cap hit |

### 2. ticket_plan (real bug: ticket log, self-review reporter and audit all say "Claude" when a free agent did the work)

| Agent | Model | Format OK | Quality | Time | Notes |
|---|---|---|---|---|---|
| OpenCode (plan agent) | openrouter/nvidia/nemotron-3.5-super-vl:free | yes (cause / changes / test / risk) | 5 | 142 s | Exact lines in server.py (`ticket_tick`, `_by_review`, `append_audit`, `tickets_mark_interrupted`), checked against the audit log. Starts with "Let me search..." narration lines. 11 steps, roughly 11 OpenRouter calls. |
| Codex | gpt-6-luna, effort low | yes | 5 | 56 s | Clean and correct. Also caught that the 429 fallback can change which agent really ran. **Uses Plus quota, 48k tokens.** |
| Hermes | nvidia/nemotron-3.5-super-vl:free | no | 1 | 30 s | No file tools, so no plan, only QUESTION lines. 5 calls. |
| OpenCode | opencode/* Zen free models | not tested | - | - | All on the Zen free limit (429) |

### 3. Job search (fake profile, 3 fake posts: good fit / needs a test task + Loom video / wrong field)

| Step | Agent | Model | Format OK | Quality | Time | Notes |
|---|---|---|---|---|---|---|
| Score (`score_batch`) | Hermes | nemotron-3-ultra-550b:free | yes, 3/3 rows | 5 | 17 s | 4.5 / 4.0 with needs_you=true (test task + Loom) / 1.0. Clear reasons. |
| Score | Hermes | nemotron-3.5-super-vl:free | yes, 3/3 rows | 5 | 61 s | 4.0 / 4.5 with needs_you=true / 1.0 |
| Draft (`draft`, OnlineJobs rules) | Hermes | nemotron-3.5-super-vl:free | raw yes (131 words, no brackets, client unnamed); **OS gets only the last 5 lines** | 4 | 59 s | Small stretch: "I can take on 15-20 reels a week" (the CV says 12-15). |
| Draft | Hermes | nemotron-3-ultra-550b:free | no: 72 words, rule is 120-180 | 3 | 63 s | Accurate but too short. |
| Form answers (`ANSWER_SYSTEM`) | Hermes | nemotron-3.5-super-vl:free | yes, all 6 ids | 5 | 37 s | US work-authorisation and salary: blank + confirm. EEO: blank, no confirm. Ignored a prompt-injection planted in a form label. |
| Form answers | Hermes | nemotron-3-ultra-550b:free | - | - | - | 429: OpenRouter daily cap |
| Score / draft | OpenCode Zen | any | not tested | - | - | Zen free limit. Scoring already runs on OpenCode Zen in production. |

### 4. session_verdict (made-up 3-agent debate: USB vs cloud backup)

| Agent | Model | Format OK | Quality | Time | Notes |
|---|---|---|---|---|---|
| Hermes | nvidia/nemotron-3-ultra-550b-a55b:free | yes (answer, Why:, They disagreed on:) | 5 | 29 s | Short and decisive. Names the one disagreement that matters. |
| Hermes | nvidia/nemotron-3.5-super-vl:free | yes | 5 | 34 s | A bit wordier, equally right. |
| Hermes | poolside/laguna-s-2.1:free | not tested | - | - | 429: daily cap |
| OpenCode | opencode/deepseek-v4.1-flash-free | no | - | 11 s | 429 `FreeUsageLimitError` (Zen) |

Both verdicts got through the 5-line cut only because each was 3 lines long.

### 5. Fallback when OpenCode Zen returns 429

Tested indirectly: the fallback gets whatever prompt OpenCode was running, which is mostly job scoring, session
turns and scheduled tasks.
- Hermes handled scoring and verdict-style text well (rows above).
- OpenCode through an `openrouter/...` model keeps its file tools and draws from a different pool than Zen
  (rows 1 and 2).
- **Code limit:** `execute_agent` calls `_execute_agent(backup, message)` without a model. If the fallback were set
  to `opencode`, it would retry the same Zen model and hit 429 again. Using an OpenRouter model there needs a model
  passed in.

## Recommendation

| Job | First choice | Backup | Free model good enough? |
|---|---|---|---|
| self_review | Hermes `nvidia/nemotron-3.5-super-vl:free` (after the 5-line fix) | OpenCode Zen free model with the logs pasted in (not tested: 429), or Hermes `nemotron-3-ultra` (after the fix) | **Yes** for spotting problems. The cause it gives is a guess, because it can't read code. |
| ticket_plan | **OpenCode** (plan agent, can read files): a Zen free model, or `openrouter/nvidia/nemotron-3.5-super-vl:free` when Zen is out | Codex `gpt-6-luna` effort low (Plus quota, not free) | **Yes, but only through OpenCode.** **Never Hermes**, yet the current default sends it there. |
| jobs: scoring | OpenCode Zen (as today) | Hermes `nemotron-3-ultra-550b:free` (17 s, 5/5) | **Yes** |
| jobs: draft, follow-up, form answers | Stay on Claude (as the current code already does) | Codex, if Jhon accepts OpenAI seeing his CV | **No, for privacy, not quality.** With fake data the free models did fine (form 5/5, draft 4/5), but real use sends his CV and profile to free models that may log prompts. |
| session_verdict | Hermes `nvidia/nemotron-3-ultra-550b-a55b:free` | Hermes `nvidia/nemotron-3.5-super-vl:free` | **Yes** |
| fallback (Zen 429) | Hermes `nvidia/nemotron-3.5-super-vl:free` for text-only work (after the fix) | OpenCode + `openrouter/...` model for work that reads files (needs the model passed through) | **Yes**, but it draws from the same 50/day OpenRouter pool as everything else on Hermes |

**Budget check:** if everything runs on Hermes, a normal day already comes close to the 50-request cap. Hermes
uses 2 calls per run, and one OpenCode-via-OpenRouter ticket plan uses about 11. Two ways to keep it working:
- Put ticket plans on OpenCode Zen, which is a separate pool, and keep OpenRouter for the rest.
- Or, if Jhon chooses, add $10 of OpenRouter credit to raise the cap to 1000 a day.

## Not tested

- **All OpenCode Zen free models** (deepseek-v4.1-flash, beam, glm-5.3-flash, mimo-v2.6-flash, nemotron-3.5-super-vl):
  the Zen free limit was already reached before testing, with retry about 4 h later.
  `opencode-go/*` needs a paid Go subscription.
- **poolside/laguna-s-2.1:free, thinkingmachines/inkling:free**, and Nemotron Ultra on form answers: OpenRouter's
  daily cap ran out partway through.
- Hermes was run with `-t todo` instead of production's `web,todo,memory,session_search`, so no personal Hermes
  history could reach a free model. These tasks are text-only, so the toolsets don't change the result.
- Follow-up drafting (`draft_followup`) was not run separately. It's the same kind of task as `draft`.
