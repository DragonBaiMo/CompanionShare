# Copyright (c) 2026 CompanionShare. MIT License.
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
if (-not (Get-Command node -ErrorAction SilentlyContinue)) { throw 'Node.js 22+ is required' }
if (-not $env:BIND) { $env:BIND = '127.0.0.1' }
if (-not $env:PORT) { $env:PORT = '8087' }
if (-not $env:DATA_FILE) { $env:DATA_FILE = '.\data\devices.json' }
node .\server\src\server.mjs
if ($LASTEXITCODE -ne 0) { throw "Server exited with code $LASTEXITCODE" }
