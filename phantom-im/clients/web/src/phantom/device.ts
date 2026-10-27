const DEVICE_ID_KEY = 'easy-im.device-id'

export function getOrCreateDeviceId(storage: Pick<Storage, 'getItem' | 'setItem'> = localStorage): string {
  try {
    const existing = storage.getItem(DEVICE_ID_KEY)
    if (existing) return existing
    const id = `web-${crypto.randomUUID()}`
    storage.setItem(DEVICE_ID_KEY, id)
    return id
  } catch {
    return `web-${crypto.randomUUID()}`
  }
}
