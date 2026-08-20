#!/usr/bin/env python3
"""Assertions over a pulled screen recording. Exits non-zero on any failure.

Checks the things a human would otherwise eyeball and get wrong: that the file really
decodes, that the tracks are the ones asked for, that pause left no frozen gap, and that
the floating pill is absent from the pixels it occupied on screen.
"""
import argparse
import json
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image

MAX_FRAME_GAP_SECONDS = 0.75  # a pause must not leave a visible freeze
NEAR_BLACK = 40               # 0-255 luminance below this counts as pill background
MAX_DARK_FRACTION = 0.01      # 1% tolerance for genuinely dark UI pixels
SILENCE_DBFS = -60.0          # mean volume below this is silence, not quiet audio
MIN_AUDIO_COVERAGE = 0.8      # audio track must span most of the video


def ffprobe(video: Path, *args: str) -> dict:
    proc = subprocess.run(
        ["ffprobe", "-v", "error", "-print_format", "json", *args, str(video)],
        capture_output=True, text=True,
    )
    if proc.returncode != 0:
        raise NotAMediaFile(proc.stderr.strip() or "ffprobe could not read the file")
    try:
        return json.loads(proc.stdout)
    except json.JSONDecodeError as e:
        raise NotAMediaFile(f"ffprobe returned unparseable output: {e}") from e


class NotAMediaFile(Exception):
    """The path exists but is not something ffprobe can decode."""


def mean_volume_dbfs(video: Path) -> float | None:
    """ffmpeg's volumedetect, which reports mean and peak level over the whole track."""
    proc = subprocess.run(
        ["ffmpeg", "-v", "info", "-i", str(video), "-af", "volumedetect", "-f", "null", "-"],
        capture_output=True, text=True,
    )
    for line in proc.stderr.splitlines():
        if "mean_volume:" in line:
            return float(line.split("mean_volume:")[1].split("dB")[0].strip())
    return None


def check_audio_is_audible(video: Path) -> list[str]:
    """Distinguishes the three cases a user reports as "no audio"."""
    info = ffprobe(video, "-show_streams", "-show_format")
    audio = [s for s in info.get("streams", []) if s.get("codec_type") == "audio"]
    if not audio:
        return ["no audio track at all -- the encoder or muxer never received audio"]

    total = float(info.get("format", {}).get("duration", 0.0))
    audio_duration = float(audio[0].get("duration") or 0.0)
    failures = []
    if total > 0 and audio_duration > 0:
        coverage = audio_duration / total
        print(f"  audio spans {audio_duration:.2f}s of {total:.2f}s ({coverage * 100:.0f}%)")
        if coverage < MIN_AUDIO_COVERAGE:
            failures.append(
                f"audio covers only {coverage * 100:.0f}% of the video; the encoder starved"
            )

    mean = mean_volume_dbfs(video)
    if mean is None:
        failures.append("could not measure volume")
    else:
        print(f"  mean volume: {mean:.1f} dBFS")
        if mean <= SILENCE_DBFS:
            failures.append(
                f"audio track is present but silent ({mean:.1f} dBFS). Either nothing was "
                "playing, or the source app opts out of playback capture"
            )
    return failures


def check_tracks(video: Path, expect_audio: bool) -> list[str]:
    failures = []
    info = ffprobe(video, "-show_streams", "-show_format")
    streams = info.get("streams", [])
    video_streams = [s for s in streams if s.get("codec_type") == "video"]
    audio_streams = [s for s in streams if s.get("codec_type") == "audio"]

    if len(video_streams) != 1:
        failures.append(f"expected exactly 1 video track, found {len(video_streams)}")
    else:
        v = video_streams[0]
        if v.get("codec_name") != "h264":
            failures.append(f"video codec is {v.get('codec_name')}, expected h264")
        if int(v.get("width", 0)) <= 0 or int(v.get("height", 0)) <= 0:
            failures.append("video track reports no dimensions")
        print(f"  video: {v.get('codec_name')} {v.get('width')}x{v.get('height')}")

    if expect_audio and len(audio_streams) != 1:
        failures.append(f"expected 1 audio track, found {len(audio_streams)}")
    elif not expect_audio and audio_streams:
        failures.append(f"expected no audio track, found {len(audio_streams)}")
    elif audio_streams:
        a = audio_streams[0]
        if a.get("codec_name") != "aac":
            failures.append(f"audio codec is {a.get('codec_name')}, expected aac")
        print(f"  audio: {a.get('codec_name')} {a.get('sample_rate')}Hz {a.get('channels')}ch")

    duration = float(info.get("format", {}).get("duration", 0.0))
    if duration <= 0.5:
        failures.append(f"duration is {duration}s; the file is effectively empty")
    print(f"  duration: {duration:.2f}s")
    return failures


def check_no_frozen_gap(video: Path) -> list[str]:
    """A pause that failed to shift timestamps shows up as one huge inter-frame gap."""
    info = ffprobe(video, "-select_streams", "v:0", "-show_entries", "frame=pts_time")
    times = sorted(
        float(f["pts_time"]) for f in info.get("frames", []) if f.get("pts_time") is not None
    )
    if len(times) < 2:
        return ["fewer than 2 video frames; cannot assess timing"]
    gaps = [b - a for a, b in zip(times, times[1:])]
    worst = max(gaps)
    print(f"  frames: {len(times)}, largest inter-frame gap {worst * 1000:.0f}ms")
    if worst > MAX_FRAME_GAP_SECONDS:
        return [f"largest frame gap {worst:.2f}s exceeds {MAX_FRAME_GAP_SECONDS}s"]
    return []


def check_pill_absent(
    video: Path, region: tuple[int, int, int, int], screen_width: int
) -> list[str]:
    """The pill's background is near-black; on a light screen its absence is measurable.

    The region comes from dumpsys in *screen* pixels, but the video may be encoded at a
    lower preset, so it is scaled into frame coordinates before cropping.
    """
    with tempfile.TemporaryDirectory() as tmp:
        pattern = str(Path(tmp) / "frame_%03d.png")
        subprocess.run(
            ["ffmpeg", "-v", "error", "-i", str(video), "-vf", "fps=1", "-frames:v", "5", pattern],
            check=True,
        )
        frames = sorted(Path(tmp).glob("frame_*.png"))
        if not frames:
            return ["no frames could be extracted"]
        worst_fraction = 0.0
        for frame in frames:
            with Image.open(frame) as img:
                scale = img.width / screen_width
                x, y, w, h = (int(v * scale) for v in region)
                if w <= 0 or h <= 0:
                    return [f"pill region {region} scales to nothing at {img.width}px wide"]
                if x + w > img.width or y + h > img.height:
                    return [
                        f"scaled pill region {(x, y, w, h)} lies outside the "
                        f"{img.width}x{img.height} frame"
                    ]
                crop = img.convert("L").crop((x, y, x + w, y + h))
                pixels = list(crop.getdata())
                dark = sum(1 for p in pixels if p < NEAR_BLACK)
                worst_fraction = max(worst_fraction, dark / len(pixels))
        print(f"  pill region darkest frame: {worst_fraction * 100:.2f}% near-black pixels")
        if worst_fraction > MAX_DARK_FRACTION:
            return [
                f"{worst_fraction * 100:.1f}% of the pill region is near-black; "
                "FLAG_SECURE did not exclude the overlay"
            ]
    return []


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--video", required=True, type=Path)
    parser.add_argument("--expect-audio", action="store_true")
    parser.add_argument(
        "--expect-sound",
        action="store_true",
        help="additionally require the audio track to be audible, not just present",
    )
    parser.add_argument(
        "--pill-region",
        help="x,y,w,h of the pill's on-screen bounds, from dumpsys window",
    )
    parser.add_argument(
        "--screen-width",
        type=int,
        default=1080,
        help="physical screen width the pill region was measured in (adb shell wm size)",
    )
    args = parser.parse_args()

    if not args.video.exists():
        print(f"FAIL: {args.video} does not exist")
        return 1

    print(f"Verifying {args.video} ({args.video.stat().st_size} bytes)")
    try:
        failures = check_tracks(args.video, args.expect_audio or args.expect_sound)
        failures += check_no_frozen_gap(args.video)
        if args.expect_sound:
            failures += check_audio_is_audible(args.video)
    except NotAMediaFile as e:
        # Usually means the pull grabbed the wrong path -- quote the remote path, which
        # contains a space: adb shell "ls '/sdcard/DCIM/Screen recordings/'"
        print(f"\nFAILED:\n  - not a readable video: {e}")
        return 1
    if args.pill_region:
        region = tuple(int(part) for part in args.pill_region.split(","))
        if len(region) != 4:
            failures.append("--pill-region needs exactly x,y,w,h")
        else:
            failures += check_pill_absent(args.video, region, args.screen_width)

    if failures:
        print("\nFAILED:")
        for f in failures:
            print(f"  - {f}")
        return 1
    print("\nAll checks passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
