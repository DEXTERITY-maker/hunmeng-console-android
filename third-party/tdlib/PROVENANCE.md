# TDLib JSON Java binding

Official source: https://github.com/tdlib/td/blob/42e6a5259551178d1dab54a22ad96d14bd906e20/example/java/org/drinkless/tdlib/JsonClient.java

Pinned TDLib commit: `42e6a5259551178d1dab54a22ad96d14bd906e20`. Boost Software License 1.0 included. Local change: remove native-load printStackTrace; native absence is handled by the app without logging. Native libraries are built from this same commit, not downloaded from third-party distributions.

OpenSSL: official release 3.5.9 LTS, SHA-256 `603f5602e2eef00d77fbd429d34dcd5822bb301757a1bc9cdb24c670f1eb859a`. NDK 28.2.13676358; Android API 26; ARM64 and x86_64; 16 KiB alignment. Build result pending.
