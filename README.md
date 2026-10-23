# Torodki

A small demolition game for Android. A building stands on a plinth in the middle of a
sunny street lined with palms. You throw a wooden hammer at it: drag a finger to the
spot you want to hit and let go. A sharp flick throws hard and flat, a lazy release
lobs. The hammer always comes back to your hand.

Every throw puts out one of the five stars in the corner. Knock three quarters of the
building's weight off the plinth and the next one rises. Run out of stars and the same
building stands up again at once, so you can try the throw you now know.

- Seven kinds of buildings (tower, seesaw, wall, well, gate, press, steps), grown from
  the level number and a seed. Each one is held still for a second before you see it,
  and a building that moves is thrown away and grown again.
- The street lives in the background: people stroll by, cars come and turn back,
  all as soft silhouettes with long shadows.
- Rigid-body physics written for the game: boxes solved on positions in small
  substeps, with friction that holds a stack still and sleep for what does not move.
- 3D drawn in software into a small pixel frame, with sun shadows and outlines.
- Every sound is synthesized: wood and stone knocks, the thud of the ground, the
  whirr of the hammer, thunder when one throw brings down half a building.

## Build

Plain Java, no build system: `build.sh` compiles against an Android platform jar and
signs with the key named in `KEYSTORE`, `KEY_ALIAS` and `KS_PASS`.

```sh
ANDROID_JAR=... KEYSTORE=... KEY_ALIAS=... KS_PASS=... ./build.sh
```

`stand/run.sh` plays the game on a desktop JVM from a scene script and saves frames
as PNG; `stand/src/.../Bot.java` plays levels on its own and reports how many throws
each took.

## License

0BSD, see `LICENSE`.
