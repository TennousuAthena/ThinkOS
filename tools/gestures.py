"""Bounded, reproducible touchscreen trajectories in display pixels and milliseconds."""
import math
import random

MAX_POINTS = 2000
MAX_MS = 10_000
DEFAULT_SIZE = (800, 1280)


def validate_path(points, *, size=None):
    checked = []
    previous = -1
    for point in points:
        if len(checked) == MAX_POINTS:
            raise ValueError('Too many touch points')
        at, x, y = point
        if type(at) is not int or not previous < at <= MAX_MS or (not checked and at != 0):
            raise ValueError('Touch times must start at zero and strictly increase')
        if not all(type(value) in (int, float) and 0 <= value < 100_000 for value in (x, y)) or (
                size is not None and (x >= size[0] or y >= size[1])):
            raise ValueError('Touch coordinates are outside the display')
        checked.append((at, float(x), float(y)))
        previous = at
    if len(checked) < 2:
        raise ValueError('A touch path needs DOWN and UP/CANCEL')
    return checked


def _sample(start, end, ms, position, *, rate, size, jitter, seed):
    if type(ms) is not int or not 1 <= ms <= MAX_MS or type(rate) is not int or not 1 <= rate <= 1000:
        raise ValueError('Invalid trajectory duration or sampling rate')
    if not 0 <= jitter <= 8:
        raise ValueError('Jitter must be between 0 and 8 pixels')
    validate_path([(0, *start), (ms, *end)], size=size)
    steps = math.ceil(ms * rate / 1000)
    if steps + 1 > MAX_POINTS:
        raise ValueError('Sampling exceeds the touch point limit')
    rng = random.Random(seed)
    points = []
    for index in range(steps + 1):
        at = round(index * ms / steps)
        fraction = position(at / ms)
        x = start[0] + fraction * (end[0] - start[0])
        y = start[1] + fraction * (end[1] - start[1])
        if 0 < index < steps and jitter:
            x += rng.uniform(-jitter, jitter)
            y += rng.uniform(-jitter, jitter)
            x = min(max(x, 0), size[0] - 1)
            y = min(max(y, 0), size[1] - 1)
        points.append((at, x, y))
    return validate_path(points, size=size)


def fling(start, end, ms, *, rate=120, size=DEFAULT_SIZE, jitter=0, seed=0):
    return _sample(start, end, ms, lambda progress: 1 - (1 - progress) ** 2,
                   rate=rate, size=size, jitter=jitter, seed=seed)


def slow_drag(start, end, ms, reverse_at=None, *, rate=120, size=DEFAULT_SIZE, jitter=0, seed=0):
    if reverse_at is not None and not 0 < reverse_at < 1:
        raise ValueError('Reversal must be inside the gesture')

    def position(progress):
        if reverse_at is None:
            return progress
        return min(progress / reverse_at, (1 - progress) / (1 - reverse_at))

    return _sample(start, end, ms, position, rate=rate, size=size, jitter=jitter, seed=seed)


def edge_swipe(edge, along, distance, ms, *, inset=6, size=DEFAULT_SIZE, **options):
    width, height = size
    if distance <= 0 or inset < 0:
        raise ValueError('Invalid edge swipe distance or inset')
    if edge == 'left':
        start, end = (inset, along), (inset + distance, along)
    elif edge == 'top':
        start, end = (along, inset), (along, inset + distance)
    elif edge == 'bottom':
        start = along, height - 1 - inset
        end = along, start[1] - distance
    elif edge == 'right':
        start = width - 1 - inset, along
        end = start[0] - distance, along
    else:
        raise ValueError('Unknown edge: ' + edge)
    return fling(start, end, ms, size=size, **options)


def tap(x, y, hold_ms=80, *, size=DEFAULT_SIZE):
    return validate_path([(0, x, y), (hold_ms, x, y)], size=size)
