# Component catalog

Settings can load an HTTPS JSON catalog and install checksum-verified packages. A package entry identifies its type, ABI, URL, SHA-256, and optional Java generation.

```json
{
  "schemaVersion": 1,
  "packages": [
    {
      "id": "example-java-21-arm64",
      "name": "Android Java 21",
      "version": "replace-with-real-version",
      "type": "RUNTIME",
      "architecture": "arm64-v8a",
      "url": "https://your-trusted-host.example/java21-arm64.tar.xz",
      "sha256": "replace-with-64-character-sha256",
      "javaVersion": "JAVA_21"
    }
  ]
}
```

Do not publish a catalog entry until the package license permits redistribution and the package has been tested with the matching engine and renderer.
