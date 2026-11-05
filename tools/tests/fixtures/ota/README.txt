ThinkOS-owned silent video fixture, MIT. No audio track; test playback also keeps
Android STREAM_MUSIC at zero. Only packaged in the instrumentation APK.

Generated locally with:
ffmpeg -f lavfi -i color=c=black:s=160x90:r=10 -t 30 -an -c:v libx264 -pix_fmt yuv420p -movflags +faststart runtime-video.mp4
