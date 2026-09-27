#!/usr/bin/env python3
"""
F-142 / SF-142-16 — le vocabulaire qui pardonne (absorbe SF-142-14).

Le défaut constaté sur le rendu réel du 2026-09-27 : « MWAA » dessiné en pointillés rouges. L'agent
avait écrit le nom que tout le monde emploie — `aws.mwaa` — et le catalogue ne connaissait que le nom
de classe de la bibliothèque. Deux moitiés du même défaut : le catalogue ne pardonnait rien, et le
refus ne guidait pas (`aws.managedworkflowsforapacheairflow` se voyait suggérer « aws.sf »).

La règle de fond ne bouge pas : un alias est une ÉGALITÉ, jamais une ressemblance. Ces tests la
gardent — aucune icône ne doit être rendue « parce qu'elle ressemble ».

Usage : python3 tests/test_cloud_vocabulaire.py
"""
import io
import json
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stdout

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import check_catalog  # noqa: E402
import cloud  # noqa: E402

# Les sigles, avec la classe qu'ils DOIVENT désigner. Testés un par un : une liste qu'on parcourt
# sans nommer la cible laisserait passer un alias qui résout vers n'importe quoi.
SIGLES = {
    "aws.mwaa": "Airflow",
    "aws.sm": "SecretsManager",
    "aws.tgw": "TransitGateway",
    "aws.asg": "EC2AutoScaling",
    "aws.igw": "InternetGateway",
    "aws.natgw": "NATGateway",
    "aws.apigw": "APIGateway",
    "aws.r53": "Route53",
    "aws.ddb": "Dynamodb",
    "aws.msk": "ManagedStreamingForKafka",
    "onprem.k8s": "Master",
}


def sortie():
    return os.path.join(tempfile.mkdtemp(), "schema")


def types_proposes(message):
    """Les types cités par une suggestion, en liste — pour ne pas confondre « aws.sf » et « aws.sfn »."""
    if "Types proches : " not in message:
        return []
    liste = message.split("Types proches : ", 1)[1].rstrip(".")
    return [morceau.strip() for morceau in liste.split(",")]


def lignes_rendues(spec):
    """Les lignes que le programme écrit vraiment, en le faisant tourner de bout en bout."""
    capture = io.StringIO()
    vrai_stdin = sys.stdin

    class Entree:
        @staticmethod
        def read():
            return json.dumps(dict(spec, output=sortie()))

    sys.stdin = Entree()
    try:
        with redirect_stdout(capture):
            code = cloud.main()
    finally:
        sys.stdin = vrai_stdin
    assert code == 0, capture.getvalue()
    return capture.getvalue().splitlines()


def marqueur_rendu(spec):
    marqueurs = [ligne for ligne in lignes_rendues(spec) if ligne.startswith("UNKNOWN_TYPES=")]
    return marqueurs[0] if marqueurs else ""


class LesSiglesResolvent(unittest.TestCase):
    """Le nom que tout le monde emploie doit rendre la bonne icône."""

    def test_chaque_sigle_designe_la_bonne_classe(self):
        for sigle, classe in SIGLES.items():
            with self.subTest(sigle=sigle):
                trouve = cloud.resolve(sigle)
                self.assertIsNotNone(trouve, f"« {sigle} » ne résout rien")
                self.assertEqual(classe, trouve.__name__)

    def test_mwaa_rend_l_icone_officielle_airflow(self):
        # MWAA est Apache Airflow managé : la bibliothèque n'a pas d'icône AWS, et prendre « ce qui
        # ressemble » dans la famille aws mettrait un composant FAUX dans un livrable client.
        self.assertEqual("diagrams.onprem.workflow", cloud.resolve("aws.mwaa").__module__)

    def test_au_moins_six_sigles_sont_couverts(self):
        self.assertGreaterEqual(len(SIGLES), 6)

    def test_un_sigle_inconnu_ne_resout_toujours_rien(self):
        self.assertIsNone(cloud.resolve("aws.xyz"))


class AucunAliasMort(unittest.TestCase):
    """« onprem.k8s » pointait sur une classe inexistante depuis le premier jour."""

    def test_le_controle_de_build_passe(self):
        self.assertEqual([], check_catalog.alias_morts())

    def test_le_controle_de_build_fait_echouer_un_alias_mort(self):
        vrais = dict(cloud.ALIASES)
        cloud.ALIASES["aws.fantome"] = "aws.classequinexistepas"
        try:
            self.assertIn("aws.fantome -> aws.classequinexistepas", check_catalog.alias_morts())
            with redirect_stdout(io.StringIO()):
                code = check_catalog.main()
            self.assertEqual(1, code, "le build doit ÉCHOUER sur un alias mort")
        finally:
            cloud.ALIASES.clear()
            cloud.ALIASES.update(vrais)

    def test_le_controle_de_build_voit_une_famille_inconnue(self):
        vrais = dict(cloud.ALIASES)
        cloud.ALIASES["plouf.truc"] = "aws.s3"
        try:
            self.assertIn("plouf.truc (famille inconnue)", check_catalog.alias_morts())
        finally:
            cloud.ALIASES.clear()
            cloud.ALIASES.update(vrais)


class LaSuggestionGuide(unittest.TestCase):
    """Un refus sans piste ne sert à rien ; une piste alphabétique non plus."""

    def test_le_nom_long_de_mwaa_suggere_le_bon_type(self):
        proposes = types_proposes(cloud.suggestions("aws.managedworkflowsforapacheairflow"))
        self.assertIn("aws.mwaa", proposes)
        self.assertNotIn("aws.sf", proposes, "la liste alphabétique est de retour")

    def test_une_faute_de_frappe_retrouve_le_service(self):
        self.assertIn("aws.secretsmanager", types_proposes(cloud.suggestions("aws.sekretmanager")))

    def test_un_nom_presque_juste_place_le_bon_type_en_tete(self):
        self.assertEqual("aws.rds", types_proposes(cloud.suggestions("aws.rds2"))[0])

    def test_un_nom_sans_aucun_voisin_rend_quand_meme_une_piste_sans_doublon(self):
        proposes = types_proposes(cloud.suggestions("aws.zzzz"))
        self.assertTrue(proposes)
        self.assertEqual(len(set(proposes)), len(proposes), "la piste répète les mêmes types")

    def test_une_famille_inconnue_nomme_les_familles(self):
        self.assertIn("Familles connues", cloud.suggestions("plouf.truc"))

    def test_la_piste_ne_depasse_pas_sa_borne(self):
        self.assertLessEqual(len(types_proposes(cloud.suggestions("aws.zzzz"))), cloud.MAX_SUGGESTIONS)


class LaSuggestionRemonteEnModeNormal(unittest.TestCase):
    """Elle n'était donnée qu'au refus « strict » ; le mode normal disait le manque sans la piste."""

    def test_le_marqueur_porte_la_piste(self):
        marqueur = marqueur_rendu({"title": "T", "nodes": [
            {"id": "a", "type": "aws.managedworkflowsforapacheairflow", "label": "Airflow"}]})
        self.assertTrue(marqueur.startswith("UNKNOWN_TYPES="), marqueur)
        self.assertIn("aws.managedworkflowsforapacheairflow", marqueur)
        self.assertIn("aws.mwaa", marqueur)

    def test_le_marqueur_tient_sur_une_seule_ligne_ascii(self):
        # Il finit en en-tête HTTP : un accent ou un saut de ligne y casserait la réponse.
        lignes = lignes_rendues({"title": "T", "nodes": [
            {"id": "a", "type": "aws.zzzé", "label": "Bizarre"}]})
        self.assertEqual(2, len(lignes), lignes)
        lignes[1].encode("ascii")

    def test_un_schema_tout_resolu_n_emet_aucun_marqueur(self):
        self.assertEqual("", marqueur_rendu({"title": "T", "nodes": [
            {"id": "a", "type": "aws.mwaa", "label": "Airflow"}]}))

    def test_strict_refuse_toujours_avec_la_piste(self):
        with self.assertRaises(cloud.Refused) as refus:
            cloud.build({"title": "T", "strict": True, "nodes": [
                {"id": "a", "type": "aws.managedworkflowsforapacheairflow", "label": "A"}]},
                sortie())
        self.assertIn("aws.mwaa", str(refus.exception))


class AucuneResolutionApprochante(unittest.TestCase):
    """La règle de fond de F-142 : suggérer peut approcher, résoudre JAMAIS."""

    def test_un_nom_proche_mais_faux_ne_resout_rien(self):
        for faux in ("aws.secretmanager", "aws.rds2", "aws.transitgatewayy", "aws.mwaaa"):
            with self.subTest(faux=faux):
                self.assertIsNone(cloud.resolve(faux), f"« {faux} » a résolu par ressemblance")

    def test_les_types_deja_resolus_rendent_la_meme_classe(self):
        for kind, classe in {"aws.s3": "SimpleStorageServiceS3", "aws.rds": "RDS",
                             "aws.alb": "ElbApplicationLoadBalancer",
                             "aws.nlb": "ElbNetworkLoadBalancer", "aws.users": "User",
                             "onprem.postgres": "Postgresql", "aws.natgateway": "NATGateway",
                             "azure.aks": "KubernetesServices",
                             "gcp.gke": "KubernetesEngine"}.items():
            with self.subTest(kind=kind):
                self.assertEqual(classe, cloud.resolve(kind).__name__)


if __name__ == "__main__":
    unittest.main(verbosity=2)
