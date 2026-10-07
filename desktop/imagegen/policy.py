"""Conservative P100 envelope. The model chooses parameters; this policy is authoritative."""
MAX_RENDER_SECONDS = 15 * 60
MAX_LOAD_SECONDS = 90
MAX_PIXELS = 2000 * 2000
MAX_WORK = 2000 * 2000 * 8
MAX_REFERENCES = 2


def validate(request):
    w, h, steps = request['width'], request['height'], request['steps']
    refs = request.get('reference_file_ids', [])
    if not request['prompt'].strip():
        raise ValueError('Describe the image to generate')
    if w % 16 or h % 16 or not 256 <= w <= 2000 or not 256 <= h <= 2000:
        raise ValueError('Width and height must be multiples of 16 between 256 and 2000')
    if not 4 <= steps <= 12 or w * h > MAX_PIXELS or w * h * steps > MAX_WORK:
        raise ValueError('Generation exceeds the size or step limit. Maximum 2000×2000, with at most 8 steps at that size; default 768×768 at 8 steps.')
    if len(refs) > MAX_REFERENCES or len(set(refs)) != len(refs):
        raise ValueError('Use at most two distinct reference images')
    return request
