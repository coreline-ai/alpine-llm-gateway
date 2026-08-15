# Codex App Server artifact pack

This internal Android AAR packages an exact, locally provisioned OpenAI Codex arm64 executable only when
`-PcodexAppServerPackEnabled=true` or the integrated internal switch
`-PcodexAppServerEnabled=true` is supplied. Normal builds remain artifact-free; an enabled feature may not override the
pack back to OFF.

Provision the official npm tarball or extracted executable without committing it:

```bash
python3.11 scripts/import-codex-appserver-artifact.py /path/to/openai-codex-0.147.0-linux-arm64.tgz
```

The importer and Gradle task both verify the version lock. Runtime download and `latest` resolution are not supported.
The pack is intentionally excluded from the public SDK publication until release licensing and provenance review passes.
