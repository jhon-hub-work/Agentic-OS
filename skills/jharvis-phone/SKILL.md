---
name: jharvis-phone
description: Have Jharvis do tasks on Jhon's Android phone (the Jharvis phone app, a fork of Mika), follow progress, add instructions to running tasks, answer task questions, and retrieve results through the bundled CLI. Use when the user wants Mika to operate phone apps, browse, or work with phone files.
---

# Mika on your phone

Mika runs its own agent on Android. Give it the user's complete goal, target and constraints; it chooses the phone actions. The bundled CLI talks directly to Mika's task API. No MCP client or MCP configuration is required.

## Run the bundled client

Resolve this skill's directory and invoke `python3 <skill-directory>/scripts/mika_cli.py`. It is self-contained and requires Python 3.10+. Use the installed `mika` command instead if available. ADB mode additionally requires Android platform-tools and a phone already connected through USB or paired wireless debugging. The APK must include task API v1; an older MCP-only APK is insufficient.

In the examples below, `mika` is shorthand for that bundled Python invocation when no command is installed.

```bash
python3 <skill-directory>/scripts/mika_cli.py devices --json
python3 <skill-directory>/scripts/mika_cli.py connect --device SERIAL --json
python3 <skill-directory>/scripts/mika_cli.py doctor --device SERIAL --json
```

Choose an explicit device when multiple phones are online. Keep that same device for the entire task. `connect` enables the phone's computer access using Mika's official bootstrap script. Other commands read the current connection information without starting the service. The CLI keeps tokens out of output and cleans up its own temporary ADB forwarding.

For an explicitly configured private-network connection, use `--url http://PHONE:8765 --token-file /path/to/private-token-file` instead of `--device`. Do not put the token itself into a command, task goal or response. See `--help` for command syntax.

## Delegate and follow the task

Use a UTF-8 file or stdin for goals and answers containing multiline text. Installation or connectivity does not authorize additional phone actions: preserve the user's requested scope when writing the goal.

```bash
mika --device SERIAL task run --goal-file /path/to/goal.txt --json
mika --device SERIAL task wait TASK_ID --timeout 45 --json
mika --device SERIAL task status TASK_ID --json
```

Keep the returned task ID and conversation ID. A successful submission only means that Mika accepted the task. Each `wait` is bounded, returns on a status change, and never cancels or resubmits the phone task. Decide whether another wait is useful from the returned state and the user's request; do not create a background polling loop.

| Returned state | Next action |
| --- | --- |
| `starting`, `running`, `paused` | Read progress; wait again within the active task when appropriate. |
| `waiting_for_answer` | Inspect `question` and `options`. Answer from known user instructions, or ask the user for missing information. |
| `waiting_on_phone` | Tell the user what needs attention on the phone; an answer command cannot approve or complete this step. |
| `done` | Report the answer and its actual verification fields. Missing `verified` means unknown. |
| `failed`, `cancelled` | Report the recorded outcome; do not silently start the task again. |

```bash
mika --device SERIAL task answer TASK_ID --text-file /path/to/answer.txt --json
mika --device SERIAL task steer TASK_ID --text-file /path/to/additional-instructions.txt --json
mika --device SERIAL task cancel TASK_ID --json
```

Cancellation's `accepted: true` is an acknowledgement, not evidence of a cancelled terminal state. Query the same task to see its final state. `answer` only answers an open question; it does not redirect a running goal. To continue a completed conversation, submit a new task with `task run --conversation CONVERSATION_ID`.

Use `steer` only when the user adds or corrects requirements for the same `running` task. It keeps the task ID and context; `accepted: true` and `message_id` mean inbox receipt, not execution. Query `status` for that message's entry in `steers`: `received` (queued), `joined` (added to context), or `not_applied` (unused at completion/cancellation). An in-flight action finishes first and past actions are not undone. Steering cannot answer a question, approve payment, resume a pause or finish handoff. Other states reject it; do not turn the rejection into a new task. It requires an APK advertising `task_steer`; update the phone app if unsupported. Text may come from `--text`, a UTF-8 file or stdin (`--text-file -`), up to 4000 characters.

## Handle uncertain outcomes

- Exit 4 / `outcome_unknown`: a write may have reached the phone. Do not automatically repeat it. Query the known task ID, or inspect `task list` if no ID was received. If this cannot establish the outcome, report that uncertainty.
- ADB loss or phone reboot: reconnect to the same phone. The remote task index is in memory, so an app process restart can make old IDs unavailable; reconnecting does not resume an interrupted task.
- `doctor` verifies connection and available device state, and reports model configuration as unverified. It does not prove that a real phone task can succeed.
- Exit 0 can describe an active task or an accepted cancellation. Exit 1 can be a rejected operation or a task observed as failed/cancelled. Exit 2 is input, configuration or connection failure. An interrupted read exits 130 and leaves the phone task alone.
