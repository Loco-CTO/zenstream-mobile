Synthetic silent media for offline audio integration tests. No user media is included.
Both files contain twelve seconds of mono silence at 48 kHz.
Regenerate with FFmpeg:
ffmpeg -v error -y -f lavfi -i anullsrc=r=48000:cl=mono -t 12 -c:a flac -threads 1 silence.flac
ffmpeg -v error -y -f lavfi -i anullsrc=r=48000:cl=mono -t 12 -c:a aac -b:a 128k -threads 1 -movflags +faststart silence.m4a
multi.m4a contains two independent silent AAC tracks tagged English and Japanese:
ffmpeg -v error -y -f lavfi -i anullsrc=r=48000:cl=mono -f lavfi -i anullsrc=r=48000:cl=mono -map 0:a -map 1:a -t 12 -c:a aac -b:a 128k -metadata:s:a:0 language=eng -metadata:s:a:1 language=jpn -threads 1 -movflags +faststart multi.m4a
