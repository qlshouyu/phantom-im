import { describe, expect, it } from 'vitest'
import { LatestRequestTracker } from './LatestRequestTracker'

describe('LatestRequestTracker', () => {
  it('只接受最新请求，并在本地状态更新后使在途请求失效', () => {
    const tracker = new LatestRequestTracker()
    const first = tracker.begin()
    const second = tracker.begin()

    expect(tracker.isCurrent(first)).toBe(false)
    expect(tracker.isCurrent(second)).toBe(true)

    tracker.invalidate()
    expect(tracker.isCurrent(second)).toBe(false)
  })
})
