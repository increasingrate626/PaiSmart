$ErrorActionPreference = 'Stop'

$repoRoot = git rev-parse --show-toplevel
if (-not $repoRoot) {
    throw 'Not inside a Git repository.'
}

Set-Location $repoRoot

if (-not (Test-Path '.githooks/pre-push')) {
    throw 'Missing .githooks/pre-push.'
}

git config core.hooksPath .githooks

Write-Host 'Configured Git hooks for this repository.'
Write-Host 'core.hooksPath=.githooks'
Write-Host 'pre-push will run targeted Agentic RAG graph tests when related Java/config files changed.'
