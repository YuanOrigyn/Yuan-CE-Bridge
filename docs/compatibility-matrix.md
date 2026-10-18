# Compatibility Matrix

This is the intentionally small release matrix. A target is a build profile,
not a promise that every patch in its manifest range has been tested in-game.

| Component | Target | Baseline | Java | Important dependencies | Artifact |
| --- | --- | --- | --- | --- | --- |
| Paper bridge | 26.x | 26.2 | 25 | Paperweight dev bundle 26.2.build.65-beta; local CraftEngine API jar | server/build/libs/Yuan-CE-Bridge-1.3.7-26.x.jar |
| Paper bridge | 26.1 | 26.1.2 | 25 | Paperweight dev bundle 26.1.2.build.74-stable; local CraftEngine API jar | server/build/libs/Yuan-CE-Bridge-1.3.7-26.1.jar |
| Fabric client | 26.x | 26.2 | 25 | Loader 0.19.3; Fabric API 0.155.0+26.2; JEI 30.16.0.131; Jade 26.2.10 | client/build/libs/Yuan-CE-Bridge-26.x-1.2.1.jar |
| Fabric client | 26.1 | 26.1.2 | 25 | Loader 0.19.3; Fabric API 0.155.3+26.1.2; JEI 29.40.0.101; Jade 26.1.9 | client/build/libs/Yuan-CE-Bridge-26.1-1.2.1.jar |
| NeoForge client | 26.x | 26.1.2 compile baseline | 25 | NeoForge >=26.1 <27; JEI 29.40.0.101; Jade 26.1.10 | client-neoforge/build/libs/Yuan-CE-Bridge-neoforge-26.x-1.2.1.jar |
| NeoForge client | 26.1 | 26.1.2 | 25 | NeoForge 26.1.2.109; JEI 29.40.0.101; Jade 26.1.10 | client-neoforge/build/libs/Yuan-CE-Bridge-neoforge-26.1-1.2.1.jar |

## Metadata contracts

- 26.x client: Minecraft >=26.2 <27, Java >=25, Loader >=0.19.3.
- NeoForge 26.x client: Minecraft and NeoForge >=26.1 <27, Java >=25; compiled against the 26.1.2 local API baseline.
- 26.1 client: Minecraft >=26.1 <26.2, Java >=25, Loader >=0.19.3 (Fabric) or NeoForge >=26.1 <26.2.
- The client jars do not bundle net/minecraft, net/fabricmc, or mezz/jei
  runtime classes.
- The server jar is built for two Paperweight targets: 26.2 (26.x) and 26.1.2 (26.1).
  They share the src/26.x recipe-sync NMS adapter, since their recipe result and constructor
  shapes are identical.
- The bridge protocol accepts any two of the target strings it knows (`26.x`, `26.1`, `1.21.11`),
  in either direction, provided protocol version and advertised capability bits match. A client
  and a server therefore do not have to run the same Minecraft version: only the payload formats
  have to agree, and those are version independent. Unknown targets are rejected. `1.21.11` is
  still accepted for bridges deployed before that target was retired, even though this build no
  longer produces a 1.21.11 artifact.

## Evidence boundary

Build success proves compilation, remapping, packaging, and metadata checks.
It does not prove a live server reload or an in-game JEI/Jade session. Those
require a separately authorized server operation and manual runtime testing.
