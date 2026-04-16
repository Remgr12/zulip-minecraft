# Zulip Bridge

Zulip Bridge is a client-side Fabric mod that lets you read and send Zulip messages from inside Minecraft.

It provides:

- incoming Zulip messages in Minecraft chat
- outgoing messages from Minecraft to Zulip
- stream and direct message targets
- an in-game Zulip browser GUI
- markdown rendering for incoming messages
- clickable image previews
- emoji shortcode replacement and autocomplete
- Mod Menu / owo-config integration for configuration

## Requirements

- Minecraft `1.21.11`
- Fabric Loader `0.19.1` or newer
- Fabric API
- owo-lib

Mod Menu is optional, but recommended.

## Installation

1. Install Fabric Loader for Minecraft `1.21.11`.
2. Put the built mod jar in your `mods` folder.
3. Make sure Fabric API and owo-lib are also installed.
4. Start the game once so the config file is generated.

## Building

Build the mod with:

```bash
./gradlew build
```

or the equivealent for your os.

The built jar will be produced under `build/libs/`.

## Configuration

You can configure the mod through Mod Menu / owo-config, or by editing the generated config file.

Important settings:

- `zulipBaseUrl`: your Zulip organization URL, for example `https://your-org.zulipchat.com`
- `botEmail`: the Zulip account email used by the bridge
- `botApiKey`: the Zulip API key for that account
- `streamName`: default Zulip stream for outgoing stream messages
- `topicName`: default Zulip topic for outgoing stream messages
- `messageTarget`: whether outgoing messages go to a stream or a direct message thread
- `directMessageRecipients`: comma-separated Zulip email addresses for DM mode
- `commandName`: the local client command name, default `zulip`
- `enabled`: whether the bridge is active
- `suppressSelfEcho`: ignore incoming messages authored by the configured Zulip account
- `playerDisplayName`: optional custom Minecraft name to prepend in outgoing messages
- `prependPlayerDisplayNameToOutgoing`: include the Minecraft name in outgoing Zulip messages
- `incomingPrefix`: text prefix shown before incoming Minecraft chat messages
- `showIncomingPrefix`: whether that prefix is shown
- `senderColor`: sender name color in Minecraft chat
- `messageColor`: message text color in Minecraft chat
- `incomingMessageFormat`: `PLAIN_TEXT`, `RAW_MARKDOWN`, or `MARKDOWN`
- `playIncomingSound`: play a local sound for DMs or mentions
- `showIncomingToast`: show a toast for DMs or mentions
- `showSuccessfulSendMessages`: print local success messages after sending

## Zulip Account Setup

The mod uses Zulip's HTTP API. You need a Zulip account with an API key.

Typical setup:

1. Create or choose a Zulip account for the bridge.
2. Get the account's API key from Zulip.
3. Enter the Zulip URL, email, and API key in the mod config.
4. Set a default stream/topic or DM target.
5. Run `/zulip test` to verify connectivity.

The bridge reads from the configured Zulip account's event stream, so incoming visibility depends on what that account can access.

## Commands

All commands are client-side commands.

### Core commands

- `/zulip help`
- `/zulip test`
- `/zulip whoami`
- `/zulip status`
- `/zulip tree`
- `/zulip reload`
- `/zulip enable`
- `/zulip disable`
- `/zulip gui`
- `/zulip openconfig`
- `/zulip config`

### Target commands

- `/zulip target show`
- `/zulip target stream <stream> <topic>`
- `/zulip target dm <email1,email2,...>`

### Sending commands

- `/zulip send <message>`
- `/s <message>`

### Image preview command

- `/zulip preview <hash>`

This is used internally for clickable `[image]` links in Minecraft chat, but it can also be called manually.

## In-Game GUI

You can open the GUI with a configure key in the keybind settings

The GUI supports:

- browsing streams and recent direct messages
- reading message history
- sending messages to the selected destination
- opening image previews from message history
- creating a new DM
- channel polling controls

## Chat Behavior

Incoming Zulip messages are shown in Minecraft chat.

Supported behavior includes:

- sender names with clickable DM targeting when possible
- stream/source labels
- clickable normal URLs
- clickable `[image]` links for image attachments and markdown image references
- fullscreen-style image preview overlay

The image preview overlay supports:

- clicking the image link to open it
- closing with `Esc`
- closing with the `x` button

## Markdown Support

When `incomingMessageFormat` is set to `MARKDOWN`, incoming chat rendering supports:

- headings such as `#`, `##`, and `###`
- bold
- italic
- strikethrough
- inline code
- block quotes
- list items
- markdown links
- markdown image links converted into clickable `[image]` entries

`PLAIN_TEXT` removes most markdown formatting.

`RAW_MARKDOWN` keeps markdown syntax visible.

## Mentions and Autocomplete

In the Zulip GUI compose field, the mod provides autocomplete for:

- user mentions after `@`
- emoji shortcodes after `:`

You can accept suggestions with:

- `Tab`
- `Enter`
- mouse click

## Emoji Support

The mod currently supports:

- built-in emoji shortcode replacement, for example `:smile:`
- shortcode autocomplete while typing in the GUI compose field

The bundled shortcode table covers standard built-in emoji aliases.

## Notes and Limitations

- This is a client-side bridge, not a server plugin.
- Outgoing commands only affect the local player.
- The bridge uses the configured Zulip account identity for API operations.
- Image previews depend on the image being downloaded successfully.
- Custom Zulip emoji are not fully implemented yet.

## License

MIT

