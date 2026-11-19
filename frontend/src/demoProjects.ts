import type { Project } from './types.ts';
export function randomName(seed: number, length: number): string {
  const characters = '가나다라마바사아자차카타파하실험모델학습데이터분석ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
  let state = seed >>> 0;
  let name = '';
  for (let index = 0; index < length; index++) {
    state = (Math.imul(state, 1664525) + 1013904223) >>> 0;
    name += characters[state % characters.length];
  }
  return name;
}

export const demoProjects: Project[] = Array.from({length:240}, (_,index) => ({
  id: 'stress-demo-' + (index + 1),
  name: randomName(1729 + index * 7919, index < 32 ? index + 1 : 1 + ((index * 17) % 32)),
  description: 'UI 검증용 데모 프로젝트',
  model: 'Demo',
  count: index === 0 ? 240 : 1 + ((index * 73) % 240),
}));

export function includeDemoProjects(projects: Project[]): Project[] {
  const existing = new Set(projects.map(project => project.id));
  return [...projects, ...demoProjects.filter(project => !existing.has(project.id))];
}
