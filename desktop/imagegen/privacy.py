"""Incognito bypasses both prompt snapshots and the global cross-request text cache."""
async def prepare_chat(body,incognito,cache,openings):
    if incognito:
        result=dict(body)
        result['cache_prompt']=False
        return result
    result=cache.stabilize(body)
    await openings.before_chat(result)
    return result
