# Telegram Integration Guide

This guide covers setup, commands, workflows, and troubleshooting for the Telegram gateway integration.

## 1. Bot Setup

1. Open Telegram and chat with `@BotFather`.
2. Run `/newbot` and follow the prompts to create a bot.
3. Copy the bot token.
4. Configure gateway settings:

```hocon
telegram {
  enabled = true
  mode = "Webhook" # or "Polling"
  botToken = "<BOT_TOKEN>"
  secretToken = "<WEBHOOK_SECRET>"
  webhookUrl = "https://<your-host>/webhook/telegram/<BOT_TOKEN>"
  polling {
    interval = 1 second
    batchSize = 50
    timeout = 30 seconds
  }
}
```

5. Start the app:

```bash
sbt run
```

## 2. Webhook Configuration

Use Telegram Bot API `setWebhook`:

```bash
curl -X POST "https://api.telegram.org/bot<BOT_TOKEN>/setWebhook" \
  -d "url=https://<your-host>/webhook/telegram/<BOT_TOKEN>" \
  -d "secret_token=<WEBHOOK_SECRET>"
```

When using polling mode, webhook endpoints are intentionally disabled.

## 3. Command Reference

Supported bot commands:

- `/start`
- `/help`
- `/list`
- `/status <runId>`
- `/logs <runId>`
- `/cancel <runId>`

Inline keyboard actions are available for workflow controls (`details`, `cancel`, `retry`, `pause/resume toggle`).

## 4. Example Workflows

### Workflow A: Migration execution and result download

1. User sends migration request text.
2. Gateway sends progress updates.
3. Generated artifacts are zipped and uploaded as Telegram documents.
4. For very large archives, upload is chunked into multiple parts.

### Workflow B: File upload analysis

1. User uploads a document/source file.
2. Telegram channel captures `telegram.document.*` metadata.
3. Analysis runs.
4. Final report is sent back as a ZIP document.

### Workflow C: Error and retry

1. Parsing/runtime error occurs.
2. User triggers retry from inline action.
3. Gateway sends completion message when successful.

## 5. Troubleshooting

### Invalid bot token (401)

- Verify `telegram.botToken` matches the token created by BotFather.
- Ensure webhook path includes the same token.

### Invalid secret token (403)

- Check `X-Telegram-Bot-Api-Secret-Token` header in webhook requests.
- Ensure `telegram.secretToken` matches configured webhook secret.

### No incoming updates

- If in webhook mode: verify public HTTPS reachability and certificate validity.
- If in polling mode: ensure webhook is unset and polling is enabled.

### Rate limits / timeouts

- Telegram API can return `429` under burst traffic.
- Increase retry/backoff and reduce message burst size.
- Prefer chunked uploads for large files.

### Large attachment upload failures

- Confirm file exists and is readable on the gateway host.
- Ensure generated ZIP size is within Telegram constraints.
- For oversized artifacts, verify all chunk parts were uploaded.

## 6. Testing Strategy

The project includes `TelegramE2ESpec` to cover:

- End-to-end conversation flow
- Pause/resume and retry scenarios
- File upload and generated artifact download
- Concurrent multi-session routing
- Error handling (rate-limit, timeout, parse errors)
- Batch performance checks (throughput/latency bound)
