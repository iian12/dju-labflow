// Shared by k6 and the Node supervisor; durations are bounded deliberately.
export function loadProfile(name = 'smoke') {
  if (name === 'smoke') return {
    name, warmupSeconds: 40, holdSeconds: 100, rampDownSeconds: 30, recoverySeconds: 15,
    refreshSeconds: 300, stages: [
      { duration: '20s', target: 1 }, { duration: '20s', target: 5 },
      { duration: '40s', target: 5 }, { duration: '20s', target: 10 },
      { duration: '40s', target: 10 }, { duration: '20s', target: 1 },
      { duration: '10s', target: 0 },
    ],
  };
  if (name === 'soak' || name === 'soak-check') {
    const full = name === 'soak';
    const warmupSeconds = full ? 600 : 10, holdSeconds = full ? 6000 : 40;
    return {
      name, warmupSeconds, holdSeconds, rampDownSeconds: 1,
      recoverySeconds: full ? 599 : 10, refreshSeconds: full ? 300 : 15,
      stages: [{ duration: warmupSeconds + 's', target: 10 },
        { duration: holdSeconds + 's', target: 10 }, { duration: '1s', target: 0 }],
    };
  }
  throw new Error('Unknown load profile: ' + name);
}
