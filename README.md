# Stitch
Stitch is a collection of small tools used for working with and updating Tiny-format mappings. This fork is specifcally tweaked to handle mappings produced by [Merger](https://github.com/Chocohead/Merger).


## Intermediary Generation
`generateIntermediary <input-jar> <glue-mapping-file> <new-mapping-file> [-t|--target-namespace <namespace>] [-k|--keep-glue] [-p|--obfuscation-pattern <regex pattern>]...`

The [`generateIntermediary`](src/main/java/net/fabricmc/stitch/commands/CommandGenerateIntermediary.java) command is used to produce a fresh set of Intermediary mappings from an exported merged jar (`<input-jar>`) and mapping file (`<glue-mapping-file>`). It will append onto the output (`<new-mapping-file>`) if it already exists and will create it otherwise.

Normally the output namespace for the intermediaries is `intermediary`, but that can be changed via the `-t` flag to which ever name is desired. The input namespaces of `client` and `server` are not changable however.

The glue names the merged jar is mapped with are not kept in the output by default. They can be retained in the output by passing the `-k` flag. Doing so will change the namespace ordering to `intermediary` (or `-t` name), `glue`, `server`, `client`. Whilst in a production sense the glue names have little use given they are dependent on how the server and client jars were merged, compared merged jars for the purpose of updating them could benefit from having a route back from mappings dependent on the Intermediary names.

Class names are only remapped when an obfuscation pattern matches the original name, by default this is any class without a package (matched via `^[^/]*$`). Additional patterns can be specified via repeatidly using the `-p` flag, noting that the default one will not be used if additional ones are specified.


## Intermediary Updating
`updateIntermediary <old-jar> <new-jar> <old-glued-mapping-file> <new-glue-mapping-file> <new-mapping-file> <match-file> [-k|--keep-glue]`

The [`updateIntermediary`](src/main/java/net/fabricmc/stitch/commands/CommandUpdateIntermediary.java) command is used to update existing Intermediary mappings (`<old-glued-mapping-file>`) to account for the matches (`<match-file>`) between the merged jar it was generated from (`<old-jar>`) and a different merged jar (`<new-jar>`) with exported mappings (`<new-glue-mapping-file>`). The existing mappings must contain the glue names for the old jar (from passing the `-k` flag) so that the existing Intermediaries can be lifted out. It will append onto the output (`<new-mapping-file>`) if it already exists and will create it otherwise.

The output namespace of the intermediaries will be `intermediary`, the old mappings are expected to also use this.

The glue names the merged jars are mapped with are not kept in the output by default, despite the old mappings containing them. They can be retained in the output however via the `-k` flag. Doing so will change the namespace ordering to `intermediary`, `glue`, `server`, `client`.

New class names are only remapped when they don't have a package, this is unconfigurable.
