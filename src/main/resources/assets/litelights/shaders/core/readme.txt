Minecraft's core shaders

The .vsh and .fsh files in this folder are modified copies of Mojang's vanilla
Minecraft shaders, which LiteLights loads in place of Minecraft's own. The
parts that come from Minecraft are Mojang's and are not covered by LiteLights'
MIT licence; only LiteLights' own changes are. See LICENSE, Third-party
content.

Minecraft only reads .vsh, .fsh and include .glsl files from a shaders
folder, so it skips this file. Keep the name lower case: Minecraft rejects
resource paths with capital letters.
