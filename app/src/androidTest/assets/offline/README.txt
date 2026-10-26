Synthetic silent media for offline audio integration tests. No user media is included.
Both files contain twelve seconds of mono silence at 48 kHz.
Regenerate with FFmpeg:
ffmpeg -v error -y -f lavfi -i anullsrc=r=48000:cl=mono -t 12 -c:a flac -threads 1 silence.flac
ffmpeg -v error -y -f lavfi -i anullsrc=r=48000:cl=mono -t 12 -c:a aac -b:a 128k -threads 1 -movflags +faststart silence.m4a
multi.m4a contains two independent silent AAC tracks tagged English and Japanese:
ffmpeg -v error -y -f lavfi -i anullsrc=r=48000:cl=mono -f lavfi -i anullsrc=r=48000:cl=mono -map 0:a -map 1:a -t 12 -c:a aac -b:a 128k -metadata:s:a:0 language=eng -metadata:s:a:1 language=jpn -threads 1 -movflags +faststart multi.m4a
Video fixture (12 seconds, 320x180, H.264 with silent English/Japanese AAC tracks):
ffmpeg -f lavfi -i testsrc2=size=320x180:rate=24:duration=12 -f lavfi -i anullsrc=r=48000:cl=mono:d=12 -f lavfi -i anullsrc=r=48000:cl=mono:d=12 -map 0:v -map 1:a -map 2:a -c:v libx264 -preset ultrafast -crf 28 -g 24 -pix_fmt yuv420p -c:a aac -b:a 64k -metadata:s:a:0 language=eng -metadata:s:a:1 language=jpn -movflags +faststart video.mp4
