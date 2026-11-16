import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    include: ['edge/worker.spec.ts'],
    environment: 'node',
    coverage: {
      provider: 'v8',
      include: ['edge/worker.ts'],
      reportsDirectory: 'coverage/edge',
      reporter: ['text', 'lcovonly']
    }
  }
});
