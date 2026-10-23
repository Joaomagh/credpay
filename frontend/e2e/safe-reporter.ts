import type { FullResult, Reporter, TestCase, TestResult, TestStep } from '@playwright/test/reporter';

// Keep public CI output limited to static scenario/step names and aggregate counts.
export default class SafeReporter implements Reporter {
  private passed = 0;
  private failed = 0;
  private skipped = 0;
  private errors = 0;
  onTestEnd(test: TestCase, result: TestResult): void {
    if (result.status === 'passed') this.passed++;
    else if (result.status === 'skipped') this.skipped++;
    else this.failed++;
    console.log(`Browser: ${result.status} — ${test.parent.project()?.name} / ${test.title}`);
  }
  onStepEnd(_test: TestCase, _result: TestResult, step: TestStep): void {
    if (step.category === 'test.step' && step.error) console.log(`Browser: failed step — ${step.title}`);
  }
  onError(): void { this.errors++; console.log('Browser: runner error; no request data published.'); }
  async onEnd(result: FullResult): Promise<{status: 'passed' | 'failed'}> {
    console.log(`Browser: passed=${this.passed}, failed=${this.failed}, skipped=${this.skipped}, errors=${this.errors}`);
    return {status: result.status === 'passed' && this.passed === 6 && this.failed === 0
      && this.skipped === 0 && this.errors === 0 ? 'passed' : 'failed'};
  }
}
