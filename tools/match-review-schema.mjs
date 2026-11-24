// Migration plan for a copy of a verified source database.
const migrations = [
  [8, '008-skill-use-preferences.sql'],
  [9, '009-recovery-journal.sql'],
  [10, '010-computer-job-availability.sql'],
  [11, '011-expired-match-acknowledgement.sql']
];

export const reviewSchemaMarker = String(migrations.at(-1)[0]);

export function reviewMigrations(sourceMarker) {
  if (!['7', ...migrations.map(([version]) => String(version))].includes(sourceMarker))
    throw Error('Unsupported review source schema marker');
  return migrations.filter(([version]) => version > Number(sourceMarker)).map(([, file]) => file);
}
