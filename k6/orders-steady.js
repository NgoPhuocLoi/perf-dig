// Steady-rate load on GET /orders for ~2 minutes.
//
// Run (host networking, because the Quarkus dev-mode app binds to 127.0.0.1:8080
// and is not reachable from Docker's bridge network):
//
//   docker run --rm --network host -v "$PWD/k6:/scripts:ro" \
//     grafana/k6:2.3.0 run /scripts/orders-steady.js
//
// Override with -e BASE_URL=... -e RATE=... -e DURATION=...
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const RATE = Number(__ENV.RATE || 20); // requests per second
const DURATION = __ENV.DURATION || '2m';

export const options = {
  scenarios: {
    steady_orders: {
      // Open model: start RATE iterations/s no matter how slow responses are,
      // so the ~10% of 1-3s responses don't lower the request rate.
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      // About 4-5 requests are in flight at a time (RATE x ~0.23s average latency). Extra VUs absorb bursts of slow responses.
      preAllocatedVUs: 20,
      maxVUs: 100,
    },
  },
  // Default summary stops at p(95); include p(99) to see the slow tail.
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  thresholds: {
    http_req_failed: ['rate<0.01'],
    // Starts were skipped because no VU was free, so the rate wasn't actually steady
    dropped_iterations: ['count==0'],
  },
};

export default function () {
  const res = http.get(`${BASE_URL}/orders`, { tags: { name: 'GET /orders' } });
  check(res, { 'status is 200': (r) => r.status === 200 });
}
