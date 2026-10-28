export class LatestRequestTracker {
  private version = 0

  begin(): number {
    return ++this.version
  }

  invalidate(): void {
    this.version += 1
  }

  isCurrent(version: number): boolean {
    return version === this.version
  }
}
