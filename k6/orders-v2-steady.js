// Steady-rate load on GET /orders/v2 and GET /orders/v2-steady, ~2 minutes,
// both endpoints at the same rate in parallel.
//
// Run (host networking, because the Quarkus dev-mode app binds to 127.0.0.1:8080
// and is not reachable from Docker's bridge network):
//
//   docker run --rm --network host -v "$PWD/k6:/scripts:ro" \
//     grafana/k6:2.3.0 run /scripts/orders-v2-steady.js
//
// Override with -e BASE_URL=... -e RATE=... -e DURATION=...
// RATE is per endpoint, so total load is 2 x RATE.
import http from 'k6/http';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const RATE = Number(__ENV.RATE || 20); // requests per second, per endpoint
const DURATION = __ENV.DURATION || '2m';

// One duration metric per endpoint so the summary shows each endpoint's
// percentiles separately (http_req_duration alone mixes both together).
const v2Duration = new Trend('orders_v2_duration', true);
const v2SteadyDuration = new Trend('orders_v2_steady_duration', true);

// Open model: start RATE iterations/s no matter how slow responses are.
// About 2-3 requests per endpoint are in flight at a time (RATE x ~0.11s);
// the extra VUs absorb bursts of 1s responses from /orders/v2.
const steady = (exec) => ({
  executor: 'constant-arrival-rate',
  exec,
  rate: RATE,
  timeUnit: '1s',
  duration: DURATION,
  preAllocatedVUs: 10,
  maxVUs: 50,
});

export const options = {
  scenarios: {
    orders_v2: steady('ordersV2'),
    orders_v2_steady: steady('ordersV2Steady'),
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  thresholds: {
    http_req_failed: ['rate<0.01'],
    // Starts were skipped because no VU was free, so the rate wasn't actually steady
    dropped_iterations: ['count==0'],
  },
};

function hit(path, trend) {
  const res = http.get(`${BASE_URL}${path}`, { tags: { name: `GET ${path}` } });
  check(res, { [`${path} status is 200`]: (r) => r.status === 200 });
  trend.add(res.timings.duration);
}

export function ordersV2() {
  hit('/orders/v2', v2Duration);
}

export function ordersV2Steady() {
  hit('/orders/v2-steady', v2SteadyDuration);
}
