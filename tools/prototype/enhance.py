"""OpenCV prototype for Photo Unifier's processing routes.

The Android app chooses a route with ML Kit. This standalone prototype accepts a
manual --mode because it does not embed ML Kit's model.
"""
import argparse
from pathlib import Path

import cv2
import numpy as np

MAX_SIDE = 4096
NEUTRAL_CHROMA = 14.0
MIN_NEUTRAL_FRACTION = 0.05
WB_MIN, WB_MAX = 0.85, 1.2

CONFIGS = {
    "interior": dict(target=0.50, alpha=0.50, gain_min=0.62, gain_max=2.25,
                     clahe=2.0, saturation=1.12, sharp=0.24, white_balance=True),
    "exterior": dict(target=0.46, alpha=0.52, gain_min=0.55, gain_max=1.95,
                     clahe=1.8, saturation=1.08, sharp=0.22, white_balance=False),
    "auto": dict(target=0.50, alpha=0.45, gain_min=0.65, gain_max=2.20,
                 clahe=2.0, saturation=1.08, sharp=0.25, white_balance=True),
    "poster": dict(target=0.50, alpha=0.30, gain_min=0.78, gain_max=1.60,
                   clahe=1.45, saturation=1.02, sharp=0.16, white_balance=False),
}


def gray_world(rgb):
    """Estimate white balance from near-neutral, midtone pixels only."""
    lab = cv2.cvtColor(rgb, cv2.COLOR_RGB2LAB)
    _, a, b = cv2.split(lab)
    chroma = np.hypot(a.astype(np.float32) - 128.0, b.astype(np.float32) - 128.0)
    lightness = lab[:, :, 0]
    mask = ((chroma < NEUTRAL_CHROMA) & (lightness > 40) & (lightness < 235)).astype(np.uint8)
    if float(mask.mean()) < MIN_NEUTRAL_FRACTION:
        return rgb
    means = cv2.mean(rgb, mask)[:3]
    gray = sum(means) / 3.0
    channels = cv2.split(rgb)
    corrected = [cv2.convertScaleAbs(channel, alpha=float(np.clip(gray / max(mean, 1e-3), WB_MIN, WB_MAX)))
                 for channel, mean in zip(channels, means)]
    return cv2.merge(corrected)


def local_tone_map(lightness, config):
    h, w = lightness.shape
    normalized = lightness.astype(np.float32) / 255.0
    small = cv2.resize(normalized, (max(w // 8, 1), max(h // 8, 1)), interpolation=cv2.INTER_AREA)
    sigma = max(small.shape) / 20.0
    illumination = cv2.GaussianBlur(small, (0, 0), sigma)
    illumination = cv2.resize(illumination, (w, h), interpolation=cv2.INTER_LINEAR)
    gain = np.power(config["target"] / (illumination + 1e-3), config["alpha"])
    gain = np.clip(gain, config["gain_min"], config["gain_max"])
    return (np.clip(normalized * gain, 0.0, 1.0) * 255.0 + 0.5).astype(np.uint8)


def remove_paper_shadows(lightness):
    """Flatten broad illumination changes on a paper's L channel, not its colours."""
    h, w = lightness.shape
    normalized = lightness.astype(np.float32) / 255.0
    small = cv2.resize(normalized, (max(w // 8, 1), max(h // 8, 1)), interpolation=cv2.INTER_AREA)
    sigma = max(max(small.shape) / 7.0, 1.0)
    illumination = cv2.GaussianBlur(small, (0, 0), sigma)
    illumination = cv2.resize(illumination, (w, h), interpolation=cv2.INTER_LINEAR)
    gain = 0.92 / (illumination + 1e-3)
    gain = np.clip(gain, 0.70, 1.90)
    corrected = np.clip(normalized * gain, 0.0, 1.0)
    return (corrected * 255.0 + 0.5).astype(np.uint8)


def _order_corners(points):
    points = np.asarray(points, dtype=np.float32)
    center = points.mean(axis=0)
    angles = np.arctan2(points[:, 1] - center[1], points[:, 0] - center[0])
    around = points[np.argsort(angles)]
    start = int(np.argmin(around.sum(axis=1)))
    return np.roll(around, -start, axis=0)


def _max_corner_cosine(points):
    maximum = 0.0
    for index in range(4):
        previous = points[(index - 1) % 4] - points[index]
        following = points[(index + 1) % 4] - points[index]
        denominator = float(np.linalg.norm(previous) * np.linalg.norm(following))
        if denominator <= 1e-6:
            return 1.0
        cosine = abs(float(np.dot(previous, following)) / denominator)
        maximum = max(maximum, cosine)
    return maximum


def rectify_page(bgr):
    """Rectify the largest plausible four-corner page/poster; otherwise keep input."""
    gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
    blurred = cv2.GaussianBlur(gray, (5, 5), 0)
    edges = cv2.Canny(blurred, 45, 135)
    closed = cv2.morphologyEx(
        edges, cv2.MORPH_CLOSE,
        cv2.getStructuringElement(cv2.MORPH_RECT, (3, 3)),
    )
    contours, _ = cv2.findContours(closed, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    total_area = float(bgr.shape[0] * bgr.shape[1])
    best_corners, best_area = None, 0.0
    for contour in sorted(contours, key=cv2.contourArea, reverse=True)[:18]:
        perimeter = cv2.arcLength(contour, True)
        if perimeter <= 0:
            continue
        approximation = cv2.approxPolyDP(contour, 0.02 * perimeter, True)
        points = approximation.reshape(-1, 2).astype(np.float32)
        if len(points) != 4 or not cv2.isContourConvex(approximation):
            continue
        area = abs(cv2.contourArea(approximation))
        if area < total_area * 0.18 or area > total_area * 0.96:
            continue
        if _max_corner_cosine(points) >= 0.55:
            continue
        if area > best_area:
            best_corners, best_area = _order_corners(points), area

    if best_corners is None:
        return bgr
    tl, tr, br, bl = best_corners
    width = int(np.clip(max(np.linalg.norm(tr - tl), np.linalg.norm(br - bl)), 2, MAX_SIDE))
    height = int(np.clip(max(np.linalg.norm(bl - tl), np.linalg.norm(br - tr)), 2, MAX_SIDE))
    if width < 80 or height < 80:
        return bgr
    target = np.array([[0, 0], [width - 1, 0], [width - 1, height - 1], [0, height - 1]],
                      dtype=np.float32)
    transform = cv2.getPerspectiveTransform(best_corners, target)
    return cv2.warpPerspective(
        bgr, transform, (width, height), flags=cv2.INTER_CUBIC,
        borderMode=cv2.BORDER_REPLICATE,
    )


def _apply_clahe(lightness, clip_limit):
    return cv2.createCLAHE(clipLimit=clip_limit, tileGridSize=(8, 8)).apply(lightness)


def _sharpen(rgb, strength):
    if strength <= 0:
        return rgb
    blur = cv2.GaussianBlur(rgb, (0, 0), 1.6)
    return cv2.addWeighted(rgb, 1.0 + strength, blur, -strength, 0)


def enhance(bgr, mode="auto"):
    """Enhance one image using its selected route; auto is conservative/general mode."""
    if mode not in {"document", *CONFIGS.keys()}:
        raise ValueError(f"Unsupported mode: {mode}")

    source = rectify_page(bgr) if mode in {"document", "poster"} else bgr.copy()
    rgb = cv2.cvtColor(source, cv2.COLOR_BGR2RGB)

    if mode == "document":
        lab = cv2.cvtColor(rgb, cv2.COLOR_RGB2LAB)
        lightness, a, b = cv2.split(lab)
        lightness = remove_paper_shadows(lightness)
        lightness = _apply_clahe(lightness, 1.45)
        rgb = cv2.cvtColor(cv2.merge([lightness, a, b]), cv2.COLOR_LAB2RGB)
        return cv2.cvtColor(_sharpen(rgb, 0.12), cv2.COLOR_RGB2BGR)

    config = CONFIGS[mode]
    if config["white_balance"]:
        rgb = gray_world(rgb)
    lab = cv2.cvtColor(rgb, cv2.COLOR_RGB2LAB)
    lightness, a, b = cv2.split(lab)
    lightness = local_tone_map(lightness, config)
    lightness = _apply_clahe(lightness, config["clahe"])
    rgb = cv2.cvtColor(cv2.merge([lightness, a, b]), cv2.COLOR_LAB2RGB)
    hsv = cv2.cvtColor(rgb, cv2.COLOR_RGB2HSV)
    h, s, v = cv2.split(hsv)
    s = cv2.convertScaleAbs(s, alpha=config["saturation"])
    rgb = cv2.cvtColor(cv2.merge([h, s, v]), cv2.COLOR_HSV2RGB)
    return cv2.cvtColor(_sharpen(rgb, config["sharp"]), cv2.COLOR_RGB2BGR)


def stats(name, bgr):
    gray = cv2.cvtColor(bgr, cv2.COLOR_BGR2GRAY)
    print(f"{name}: mean={gray.mean():.1f} std={gray.std():.1f} "
          f"burnt(>=250)={100 * (gray >= 250).mean():.1f}% "
          f"dark(<=30)={100 * (gray <= 30).mean():.1f}%")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--mode", choices=["auto", "interior", "exterior", "document", "poster"], default="auto")
    args = parser.parse_args()

    image = cv2.imread(str(args.input), cv2.IMREAD_COLOR)
    if image is None:
        parser.error(f"Could not read input image: {args.input}")
    result = enhance(image, args.mode)
    stats("before", image)
    stats("after ", result)
    side_by_side = np.hstack([image, result])
    if not cv2.imwrite(str(args.output), side_by_side, [cv2.IMWRITE_JPEG_QUALITY, 90]):
        parser.error(f"Could not write output image: {args.output}")


if __name__ == "__main__":
    main()