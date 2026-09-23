#!/usr/bin/env python3
"""
Vérifie que CHAQUE entrée du catalogue d'icônes existe vraiment (F-142 / SF-142-07).

C'est le test qui manquait à SF-142-01 transposé ici : une entrée peut être écrite, jolie, et ne
désigner aucune classe réelle — le défaut ne se verrait qu'au premier schéma d'un client.
"""
import sys

from cloud import CATALOG


def main():
    missing = []
    for kind, (module, name) in sorted(CATALOG.items()):
        try:
            imported = __import__(module, fromlist=[name])
            getattr(imported, name)
        except Exception as error:  # noqa: BLE001
            missing.append(f"{kind} -> {module}.{name} ({type(error).__name__}: {error})")
    if missing:
        print("Entrées invalides :")
        for line in missing:
            print("  - " + line)
        return 1
    print(f"Catalogue valide : {len(CATALOG)} types d'icônes, tous importables.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
