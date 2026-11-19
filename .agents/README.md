# Codex setup for SkyWing Windows

## Already in this repository
Codex detects the project-scoped skills in `.agents/skills/` when launched anywhere under the repository root:

- `skywing-desktop-design`: Compose Desktop UI and interaction design.
- `skywing-debugging`: evidence-led crash, hang and regression investigation.
- `skywing-video-performance`: playback, libmpv, smoothing, GPU and HDR measurement.
- `skywing-release-qa`: tests, installers, upgrade identity, packaging and licensing.

`AGENTS.md` carries always-on repository instructions. **No global skills or plugins are installed by merging these files.** After checking out the changed branch on your PC, restart Codex if the skills do not appear in `/skills`.

## Optional tools installed locally on your Windows PC

In an up-to-date Codex CLI session, the official Microsoft Windows development plugins can be installed via PowerShell:

```powershell
codex plugin marketplace add microsoft/win-dev-skills
codex plugin add windbg@win-dev-skills
codex plugin add winappcli@win-dev-skills
```

- `windbg` assists with native application crashes and hangs. Installing the plugin is not necessarily the same as installing or configuring WinDbg itself.
- `winappcli` assists with certain Windows automation, signing and packaging tasks. SkyWing's authoritative EXE/MSI build remains the existing Compose Desktop Gradle setup.
- Do **not** install the WinUI plugin to develop SkyWing's UI. SkyWing uses Kotlin + Compose Desktop, not WinUI 3.

Optional third-party planning/debugging and desktop design skills:

```powershell
npx skills add https://github.com/obra/superpowers --agent codex
npx skills add https://github.com/bateau84/skills --skill desktop-ui-design --agent codex
```

Review third-party source and the CLI's installation prompts before approving changes to your local Codex environment. Skill repositories and their install flags can change.

## Verify

1. Run `codex --version`; if the `plugin` command is unavailable, update your local Codex CLI before running the optional commands.
2. Open SkyWing in Codex and run `/skills` to confirm the four repo-local skills appear.
3. For a quick smoke test, ask: `Use $skywing-debugging to identify a reproducible fullscreen regression test; make no code changes yet.`
4. Run only Windows verification on a Windows x64 host with JDK 17, per `AGENTS.md`.

Source references:
- https://learn.chatgpt.com/docs/build-skills
- https://github.com/microsoft/win-dev-skills
- https://github.com/obra/superpowers
- https://github.com/bateau84/skills
