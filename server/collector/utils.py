import subprocess
import hashlib

def run_ffmpeg(command_args, capture_output=True, check=True, text=True):
    """Runs an ffmpeg command with the specified arguments."""
    cmd = ['ffmpeg'] + command_args
    try:
        result = subprocess.run(cmd, capture_output=capture_output, check=check, text=text)
        if result.returncode != 0:
            print(f"ffmpeg failed with error: {result.stderr}")
        return result
    except subprocess.CalledProcessError as e:
        print(f"ffmpeg process error: {e}")
    except Exception as e:
        print(f"Unexpected error running ffmpeg: {e}")

def run_ffprobe(command_args, capture_output=True, check=True, text=True):
    """Runs an ffprobe command with the specified arguments."""
    cmd = ['ffprobe'] + command_args
    try:
        result = subprocess.run(cmd, capture_output=capture_output, check=check, text=text)
        if result.returncode != 0:
            print(f"ffprobe failed with error: {result.stderr}")
        return result
    except subprocess.CalledProcessError as e:
        print(f"ffprobe process error: {e}")
    except Exception as e:
        print(f"Unexpected error running ffprobe: {e}")

def trim_video(video, start_time, end_time, output_filename):
    """Trim the video using ffmpeg."""
    command_args = [
        '-v', 'error', '-y',
        '-ss', start_time,
        '-to', end_time,
        '-i', video,
        '-an', '-c:v', 'copy',
        output_filename
    ]
    
    result = run_ffmpeg(command_args)
    if result and result.returncode == 0:
        print(f"Video trimmed successfully: {output_filename}")
    else:
        print(f"Failed to trim video: {result.stderr if result else 'No result returned'}")

def compute_md5(file_path, chunk_size=8192):
    """Compute the MD5 hash of a file."""
    md5 = hashlib.md5()
    with open(file_path, "rb") as f:
        for chunk in iter(lambda: f.read(chunk_size), b""):
            md5.update(chunk)
    
    return md5.hexdigest()