# GitHub App identity for agents

Each agent uses its own GitHub App. Codex acts as `grognard-codex[bot]` and
Claude acts as `grognard-claude[bot]`. GitHub attributes pushes, PRs, and
comments to the app that performed them. The maintainer can formally approve
PRs opened by either bot.

If app authentication fails, diagnose and retry with the app. Using the
maintainer's GitHub identity for a write, including through the connected
GitHub account, requires an explicit request for that action. A publishing
handoff authorizes the work under the configured app identity.

One app can serve every repository on the account. The tooling lives in this
repository, but once set up it works for Codex in any repository the app is installed on.

## How it fits together

- **The app** is an identity you register once. It owns a set of permissions and one
  or more **private keys**.
- **An installation** connects the app to repositories. The app can only touch
  repositories it is installed on, and only with the permissions you granted.
- **Tokens** are minted on demand. The app signs a short JSON Web Token (JWT) with its
  private key, and GitHub exchanges that for an **installation token** that lasts one
  hour. gh and git use the installation token like a personal access token.

[`tools/github-app/github-app-token.ps1`](../../tools/github-app/github-app-token.ps1)
does the minting, caches the token (encrypted with Windows DPAPI) and refreshes it
before it expires. The [gh shim](../../tools/github-app/shim/gh.ps1) refreshes the token
before each gh command. Codex's own settings, or [`enter.ps1`](../../tools/github-app/enter.ps1)
for a terminal session, point gh and git at the app; your own sessions are unchanged.

## One-time setup (maintainer)

1. **Register the app.** GitHub → Settings → Developer settings → GitHub Apps →
   New GitHub App.
   - Name: for example `grognard-codex`. It becomes the bot's login, `grognard-codex[bot]`.
   - Homepage URL: the repository URL.
   - Webhook: clear **Active**. Nothing needs to receive events.
   - Repository permissions:
     - Contents: Read and write
     - Pull requests: Read and write
     - Issues: Read and write
     - Actions: Read-only
     - Checks: Read-only
     - Commit statuses: Read-only
     - Workflows: Read and write, only if Codex should edit `.github/workflows`
     - Metadata: Read-only (added automatically)
   - Where can this app be installed: **Only on this account**.
2. **Note the Client ID** on the app's General page (it starts with `Iv`).
3. **Generate a private key** on the same page. The browser downloads a `.pem` file.
   This key is the app's password; anyone holding it can act as the app.
4. **Install the app.** Install App → your account → **All repositories**, or
   **Only select repositories** to name the ones Codex works in.
5. **Store the key outside the repository** and restrict it to your Windows account.
   This guide keeps it in `C:\secure\grognard-codex`. A folder under `C:\`
   inherits Modify access for every signed-in account, so remove inheritance and grant
   only your account and SYSTEM:

   ```powershell
   $keyFolder = 'C:\secure\grognard-codex'
   New-Item -ItemType Directory -Force $keyFolder | Out-Null
   Move-Item "$HOME\Downloads\grognard-codex.*.private-key.pem" $keyFolder
   icacls $keyFolder /inheritance:r /grant:r "$($env:USERNAME):(OI)(CI)F" 'SYSTEM:(OI)(CI)F'
   icacls "$keyFolder\*" /reset
   icacls $keyFolder
   ```

   The last command should list only your account and `NT AUTHORITY\SYSTEM`.

6. **Write `config.json`** in the tool's data folder, `%LOCALAPPDATA%\mutp-github-app`.
   It holds the client ID and the key's path, never the key itself:

   ```powershell
   $dataFolder = Join-Path $env:LOCALAPPDATA 'mutp-github-app'
   New-Item -ItemType Directory -Force $dataFolder | Out-Null
   @{
     clientId       = 'Iv23...'
     privateKeyPath = (Resolve-Path 'C:\secure\grognard-codex\grognard-codex.*.private-key.pem').Path
     owner          = 'the-grognard-codes'
   } | ConvertTo-Json | Set-Content "$dataFolder\config.json"
   ```

   Tokens then cover every repository the app is installed on for that account. To
   limit them to one repository, add `repo = '<name>'`. If you later rotate the key,
   update `privateKeyPath` to the new file's name.

7. **Check it.** This mints a first token and prints the app, its scope and the
   expiry, never the token itself:

   ```powershell
   ./tools/github-app/github-app-token.ps1
   ```

## Running Codex as the app

Both ways below set the same things, and only for the commands Codex runs:

- `PATH` starts with [`tools/github-app/shim`](../../tools/github-app/shim), so `gh`
  runs the shim, which refreshes the token when under 15 minutes remain and then runs
  the real gh with the same arguments and input.
- `GH_CONFIG_DIR` points gh at a config folder holding the app's current token.
  gh has no hook for fetching tokens, so every mint rewrites that folder's `hosts.yml`.
- `GIT_CONFIG_COUNT`, `GIT_CONFIG_KEY_n` and `GIT_CONFIG_VALUE_n` replace Git Credential
  Manager with the token script for `https://github.com` only. Git asks the script for
  a credential on each push or fetch, and the script refreshes the token when needed.
- `GIT_AUTHOR_*` and `GIT_COMMITTER_*` set the commit identity to the bot, using the
  bot's noreply address so GitHub links the commits to it.

### Codex VS Code extension (or desktop app)

The extension reads `~/.codex/config.toml` and applies its
`[shell_environment_policy.set]` section to every command Codex runs. Print the lines
to put there:

```powershell
./tools/github-app/github-app-token.ps1 -Mode codex-config
```

The output keeps your existing `PATH` and any `GIT_CONFIG_*` entries already in that
section, and renumbers them with the app's entries added. In `config.toml`, under
`[shell_environment_policy.set]`, delete the existing `PATH`, `GIT_CONFIG_*`,
`GH_CONFIG_DIR`, `GIT_AUTHOR_*` and `GIT_COMMITTER_*` lines and paste the output in
their place (TOML rejects duplicate keys). Then reload the VS Code window.

Your own work in VS Code (the Source Control panel and the integrated terminal) keeps
your identity. Run `-Mode codex-config` again if you move this checkout, change your
PATH, or rename the app, because the printed lines contain absolute paths and the
bot's name.

### Codex CLI in a terminal

From a PowerShell 7 session:

```powershell
. ./tools/github-app/enter.ps1
codex
```

Close the session, or run `. ./tools/github-app/exit.ps1`, to go back to your own identity.

## A second machine

[`setup-machine.ps1`](../../tools/github-app/setup-machine.ps1) carries out steps 3, 5, 6
and 7 and the Codex setup on another machine. See
[the machine setup guide](github-app-machine-setup.md). To do it by hand, repeat those
steps on each machine where Codex runs, then set up Codex as described above. The scripts come with the repository; the key, `config.json`, token cache and
Codex settings don't.

- **Generate a separate key for each machine** (step 3 again, on the app's page) instead
  of copying the first one. An app can hold several keys, so losing one machine means
  deleting that machine's key only.
- The token cache is encrypted for one Windows account on one machine, so it can't be
  copied anyway. Each machine mints its own tokens; both act as the same bot.

## Running a second agent app on the same machine

Register and install the second app as described in [one-time setup](#one-time-setup-maintainer).
Use a separate key folder and set `MUTP_GITHUB_APP_HOME` to a separate
data folder. This gives the second app its own config, token cache, and
`gh` configuration. Run this from the checkout in a dedicated PowerShell 7 session.

```powershell
$env:MUTP_GITHUB_APP_HOME = Join-Path $env:LOCALAPPDATA 'mutp-github-app-claude'
./tools/github-app/setup-machine.ps1 `
  -AppSlug 'grognard-claude' `
  -ClientId '<Claude app client ID>' `
  -KeyFolder 'C:\secure\grognard-claude' `
  -SkipCodexConfig
. ./tools/github-app/enter.ps1
```

Use the second app's Client ID from its GitHub settings. Launch Claude from
that session so its `gh` and Git commands inherit the second app's identity.
`-SkipCodexConfig` keeps Codex on its existing app configuration.

## Requiring approval

Two active rulesets govern `main`.

- `main-branch-protection` requires a PR, one formal approving review, and
  the required status, code quality, and code scanning checks. GitHub dismisses
  stale approvals on push.
- `main-agent-lock` restricts updates to configured bypass actors. The
  maintainer handles updates to `main` through the administrative bypass.
  The agent apps use topic branches and PRs.

Claude posts a comment review. The maintainer provides formal approval and
handles the merge under the [delivery workflow](feature-workflow.md#pr-review-and-merge).
Check the current rules and required checks before handing off the PR.

```powershell
gh api repos/the-grognard-codes/moles-under-the-pitch/rules/branches/main
gh pr checks --required
```

## Rotating or revoking

- **Rotate:** generate a new key on the app page, put it in the key folder, update
  `privateKeyPath` in `config.json`, then delete the old key on the app page.
- **Revoke at once:** suspend or uninstall the installation (Settings → Applications →
  Installed GitHub Apps). Tokens already minted stop working.
- **Clear local state:** delete `%LOCALAPPDATA%\mutp-github-app\token.json` to force a
  fresh mint; delete the whole folder to remove the app from this machine.

## Using the same pattern for a team repository

- Register the app under the **organization** (Organization settings → Developer
  settings) so it doesn't depend on one person's account. Installing it may need an
  organization owner's approval.
- In GitHub Actions, mint tokens with `actions/create-github-app-token`, storing the
  client ID as a variable and the private key as a secret.
- Give each machine or environment its own private key. An app can hold several keys,
  so you can revoke one environment without affecting the others.
- Installations in organizations with more than 20 repositories or members get a
  higher limit, up to 12,500 requests an hour; on GitHub Enterprise Cloud it's 15,000.
