"""The ffmpeg command line of the latency test source: black 1280x720 with a marker box and 20 box bits = the frame's content time in ms."""
BITS, SIZE, STEP, X0, Y0 = 20, 48, 62, 16, 16

def video_filter(clock_log=False):
    boxes = ['drawbox=x=%d:y=%d:w=%d:h=%d:color=white:t=fill' % (X0, Y0, SIZE, SIZE)]
    for k in range(BITS):
        boxes.append("drawbox=x=%d:y=%d:w=%d:h=%d:color=white:t=fill:enable='bitand(trunc(t*1000),%d)'" % (X0 + STEP * (k + 1), Y0, SIZE, SIZE, 1 << k))
    return 'color=c=black:s=1280x720:r=30,' + ','.join(boxes) + ',format=yuv420p' + (',showinfo' if clock_log else '')


def h264_command(ffmpeg='ffmpeg', clock_log=False):
    """The same picture as `command`, as a bare Annex-B H.264 stream with access unit delimiters (what a screen mirror is made from), no sound."""
    return ([ffmpeg, '-nostdin', '-hide_banner', '-loglevel', 'info' if clock_log else 'error', '-re', '-f', 'lavfi', '-i', video_filter(clock_log),
             '-c:v', 'libx264', '-preset', 'ultrafast', '-tune', 'zerolatency', '-profile:v', 'baseline', '-g', '30', '-bf', '0', '-b:v', '3M', '-maxrate', '3M', '-bufsize', '1M',
             '-x264-params', 'aud=1:repeat-headers=1', '-f', 'h264', '-'])


def command(ffmpeg='ffmpeg', real_time=True, clock_log=False):
    """[clock_log]: ffmpeg also prints one line per frame with its stream time (stderr), so a caller can learn when stream time 0 was on the wall clock."""
    boxes = ['drawbox=x=%d:y=%d:w=%d:h=%d:color=white:t=fill' % (X0, Y0, SIZE, SIZE)]          # marker, always on
    for k in range(BITS):
        boxes.append("drawbox=x=%d:y=%d:w=%d:h=%d:color=white:t=fill:enable='bitand(trunc(t*1000),%d)'" % (X0 + STEP * (k + 1), Y0, SIZE, SIZE, 1 << k))
    video = 'color=c=black:s=1280x720:r=30,' + ','.join(boxes) + ',format=yuv420p' + (',showinfo' if clock_log else '')
    return ([ffmpeg, '-nostdin', '-hide_banner', '-loglevel', 'info' if clock_log else 'error'] + (['-re'] if real_time else []) +
            ['-f', 'lavfi', '-i', video, '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000',
             '-c:v', 'libx264', '-preset', 'ultrafast', '-tune', 'zerolatency', '-profile:v', 'baseline', '-g', '30', '-bf', '0',
             '-b:v', '4M', '-maxrate', '4M', '-bufsize', '1M', '-x264-params', 'repeat-headers=1',
             '-c:a', 'aac', '-b:a', '128k', '-ac', '2',
             '-f', 'mpegts', '-muxdelay', '0', '-muxpreload', '0', '-flush_packets', '1', '-pes_payload_size', '0',
             '-mpegts_flags', '+resend_headers', '-'])
