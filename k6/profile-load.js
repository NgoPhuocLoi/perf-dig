// Steady background load for a profiling session: one endpoint, fixed arrival rate.
// Start it, then attach async-profiler while it runs.
//
//   docker run --rm --network host -v "$PWD/k6:/scripts:ro" \
//     -e ENDPOINT=/orders/db/customer-totals -e RATE=1 -e TIME_UNIT=2s -e DURATION=45s \
//     grafana/k6:2.3.0 run /scripts/profile-load.js
//
// Defaults: /orders/db/customer-totals at 1 request every 2 s for 45 s
// (leaves ~5 s warm-up before a 30 s profile plus slack at the end).
import http from 'k6/http';
import { check } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ENDPOINT = __ENV.ENDPOINT || '/orders/db/customer-totals';
const RATE = Number(__ENV.RATE || 1);
const TIME_UNIT = __ENV.TIME_UNIT || '2s';
const DURATION = __ENV.DURATION || '45s';

export const options = {
  scenarios: {
    profile_load: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: TIME_UNIT,
      duration: DURATION,
      // customer-totals takes ~7 s alone and slows down when several run at once;
      // cap at 20 VUs = the JDBC pool size, so k6 never asks for more than the pool has.
      preAllocatedVUs: 10,
      maxVUs: 20,
    },
  },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  thresholds: {
    http_req_failed: ['rate<0.01'],
    dropped_iterations: ['count==0'],
  },
};

export default function () {
  const res = http.get(`${BASE_URL}${ENDPOINT}`, { tags: { name: `GET ${ENDPOINT}` } });
  check(res, { 'status is 200': (r) => r.status === 200 });
}
