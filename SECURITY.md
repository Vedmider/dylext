# Security Policy

## Reporting a vulnerability

Please report security issues privately via
[GitHub Security Advisories](https://github.com/Vedmider/dylext/security/advisories/new)
rather than opening a public issue. You will get an acknowledgement within a few days.

## Supported versions

The latest release on `main` receives security fixes. Older versions are not maintained.

## Scope notes

Dylext indexes local source and serves it over an MCP endpoint. When exposing the HTTP
`/mcp` transport beyond localhost, put it behind your own authentication/authorization —
the server does not authenticate callers itself.
