const fold = (s) => s.toLowerCase().normalize('NFD').replace(/\p{M}+/gu, '');

/** Tâches dont le titre, la description, une étiquette ou le projet contiennent tous les mots de la requête. */
export function searchTasks(tasks, projects, query) {
  const words = fold(query).split(/\s+/).filter(Boolean);
  if (!words.length) return [];
  const projectTitle = new Map(projects.map((p) => [p.id, p.title]));
  return tasks.filter((t) => {
    const hay = fold([t.title, t.description, projectTitle.get(t.projectId) || '', ...t.labels.map((l) => l.title)].join(' '));
    return words.every((w) => hay.includes(w));
  });
}

export { fold };
