# Clarity <img src="./ui/src/main/resources/META-INF/pluginIcon.svg" width=20px>

IntelliJ plugin that adds a filter to usages that are cluttering user space.

One of the primary use-cases is to hide usages that are directly or indirectly exclusively used by test functions.

This started as a fork of [Filter Out Rust Tests](https://github.com/franfrandev/filter_out_rust_tests) and is now
expanding to other languages.

## Disclaimer

This plugin is still in alpha and isn't published yet. Because of this, you may want to "install it from disk".

Here is a guide: https://www.jetbrains.com/help/idea/managing-plugins.html#install_plugin_from_disk
To avoid having to build the plugin from source, you can download the pre-built JAR file from the "releases" page.

## Support of languages

| Language | Status |
|----------|--------|
| Rust     | ✅     |
| Go       | ⏳     |

Have any language that you need to support, please open an issue or a pull request.

## Development

- Run IDE for manual testing:

```bash
./gradlew runIde
```

- Build plugin JAR:

```bash
./gradlew build
```
