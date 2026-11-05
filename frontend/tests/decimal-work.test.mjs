import assert from 'node:assert/strict';
import test from 'node:test';
import { Worker, isMainThread, parentPort, workerData } from 'node:worker_threads';

if (isMainThread) {
  for (const operation of ['display', 'identity']) {
    test(`decimal ${operation}: large exact strings finish without blocking`, async () => {
      const result = await new Promise((resolve, reject) => {
        const worker = new Worker(new URL(import.meta.url), {workerData: operation});
        let expired = false;
        const deadline = setTimeout(() => {
          expired = true;
          void worker.terminate().then(
            () => reject(new Error(`${operation}: exceeded the 3 second work budget`)), reject);
        }, 3000);
        worker.once('message', message => { clearTimeout(deadline); resolve(message); });
        worker.once('error', error => { clearTimeout(deadline); reject(error); });
        worker.once('exit', code => {
          if (code !== 0 && !expired) { clearTimeout(deadline); reject(new Error(`decimal worker exited with ${code}`)); }
        });
      });
      assert.equal(result, true, 'the exact digits and decimal identity must be preserved');
    });
  }
} else {
  // Execute the actual pure application module, not a duplicate of its algorithms.
  const { displayAmount, decimalIdentity } = await import('../src/app/transaction.ts');
  if (workerData === 'display') {
    const input = '1'.repeat(100000);
    parentPort.postMessage(displayAmount(input) === '1' + '.111'.repeat(33333) + ',00');
  } else {
    // Both strings fit the existing 65536-character journal record when used as value/receipt.
    const embeddedZeros = '1' + '0'.repeat(16382) + '1';
    const trailingZeros = '1' + '0'.repeat(16383);
    let exact = true;
    for (let repetition = 0; repetition < 50; repetition++) {
      exact = decimalIdentity(embeddedZeros) === `${embeddedZeros}:0`
        && decimalIdentity(trailingZeros) === '1:16383' && exact;
    }
    parentPort.postMessage(exact);
  }
}
