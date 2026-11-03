# Page de connexion Tuvora

Ce dossier est prêt pour le dépôt https://github.com/Titoftotof/Tuvora.

Transférer les dossiers web et .github à la racine du dépôt, en conservant leur structure.
Ne pas transférer le dossier publication-tuvora lui-même comme sous-dossier.

Dans Settings > Pages, choisir GitHub Actions comme source.
Dans Actions, lancer Publish TV login page sur la branche main.
Adresse finale : https://titoftotof.github.io/Tuvora/tv-login/

Dans Supabase :
- Authentication > Sign In / Providers : activer Anonymous Sign-Ins.
- Authentication > URL Configuration : définir Site URL et Redirect URLs à
  https://titoftotof.github.io/Tuvora/tv-login/
- Conserver la confirmation d'e-mail activée. Pour permettre de nouveaux comptes
  extérieurs à l'organisation Supabase, configurer l'envoi SMTP.

La clé intégrée à la page est une clé publique, autorisée dans le navigateur.
Aucune clé privilégiée ni donnée locale de l'application n'est incluse.
