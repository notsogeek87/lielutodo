// Appliqué avant le premier affichage pour éviter l'éclair clair en thème sombre (fichier à part : la CSP interdit les scripts inline).
try { var t = localStorage.getItem('lielutodo.theme'); if (t === 'light' || t === 'dark') document.documentElement.dataset.theme = t; } catch (e) {}
