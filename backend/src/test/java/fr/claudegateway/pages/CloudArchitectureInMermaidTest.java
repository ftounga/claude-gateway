package fr.claudegateway.pages;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Une architecture cloud ne se dessine pas à la main</b> (F-142 / SF-142-11).
 *
 * <p>Le <b>cas d'or</b> de cette classe est la vue réseau RÉELLE de la page « data-ingestion —
 * 4 vues » publiée le 2026-09-26 : un VPC, des sous-réseaux, huit endpoints, un Transit Gateway —
 * en rectangles nommés, sans une seule icône. Elle est reprise ici <b>telle quelle</b> : un test
 * écrit sur un exemple inventé n'aurait pas prouvé qu'on attrape le vrai défaut.</p>
 */
class CloudArchitectureInMermaidTest {

    /** La vue réseau réelle du 2026-09-26, copiée depuis la page publiée. */
    private static final String VUE_RESEAU_REELLE =
            "flowchart TB\n"
            + "  subgraph VPC[\"VPC eu-west-3-data-ingestion-dev-vpc — 10.180.165.0/24\"]\n"
            + "    subgraph SN[\"Sous-reseaux prives — 10.180.165.0/25 et .128/25\"]\n"
            + "      NODES[\"Noeuds EKS<br/>plage routable\"]\n"
            + "      PODS[\"Pods — 100.64.0.0/16<br/>custom networking CNI\"]\n"
            + "      MW[\"MWAA<br/>scheduler, workers, webserver\"]\n"
            + "      ALB[\"ALB interne du webserver Airflow\"]\n"
            + "      BAS[\"Bastion EC2 — AL2023<br/>acces par SSM\"]\n"
            + "      NGX[\"EC2 webtest — nginx\"]\n"
            + "      LBD[\"Lambda airflow-dag-trigger\"]\n"
            + "    end\n"
            + "    subgraph EP[\"Endpoints VPC\"]\n"
            + "      E1[\"Interface ecr.api\"]\n"
            + "      E2[\"Interface ecr.dkr\"]\n"
            + "      E3[\"Interface sqs\"]\n"
            + "      E4[\"Gateway s3\"]\n"
            + "      E5[\"Interface s3 — dediee CFT<br/>private_dns desactive\"]\n"
            + "      E6[\"Interface PrivateLink Snowflake<br/>vpce-svc-035bcf096c022b4d3\"]\n"
            + "      E7[\"Interface PrivateLink Snowflake S3\"]\n"
            + "      E8[\"Interface PrivateLink MWAA<br/>vpce-svc-0bbc9138d2b01ed29\"]\n"
            + "    end\n"
            + "    R53[\"Route 53 — 4 zones privees\"]\n"
            + "  end\n"
            + "  TGW[\"Transit gateway partagee\"]\n"
            + "  CORP[\"Reseau d'entreprise<br/>et CFT 10.112.107.17\"]\n"
            + "  NODES --> E1 & E2 & E3 & E4\n"
            + "  MW --> E4\n"
            + "  PODS --> E4\n"
            + "  NODES -->|\"0.0.0.0/0\"| TGW\n"
            + "  MW --> TGW\n"
            + "  TGW <--> CORP\n"
            + "  CORP -->|\"depot de fichiers\"| E5\n"
            + "  PODS --> E6\n"
            + "  ALB --> E8\n"
            + "  R53 -.->|\"resolution privee\"| EP\n";

    /** Une vraie vue de séquence de la MÊME page : elle doit passer. */
    private static final String VUE_SEQUENCE_REELLE =
            "sequenceDiagram\n"
            + "  participant C as CFT (CA-GIP)\n"
            + "  participant I as S3 cft-in\n"
            + "  participant E as EventBridge\n"
            + "  participant Q as SQS file-loader\n"
            + "  participant K as Conteneurs EKS\n"
            + "  participant D as S3 data-ingestion\n"
            + "  participant N as SNS ingest-snowflake\n"
            + "  participant S as Snowflake / Snowpipe\n"
            + "  participant A as MWAA Airflow\n"
            + "  participant O as S3 cft-out\n"
            + "  C->>I: depot sous incoming/risque.rrf… \n"
            + "  I->>E: evenement Object Created\n"
            + "  E->>Q: message (regle lzi-s3-source-cft-in-rule)\n"
            + "  K->>Q: lecture du message\n"
            + "  K->>D: parties, metafichiers, normalises, rejets\n"
            + "  D->>E: evenement sur prefixe files_ready/\n"
            + "  E->>N: publication (regle lzi-s3-source-files-ready-rule)\n"
            + "  N->>S: notification Snowpipe\n"
            + "  S\n";

    private static String page(String bloc) {
        return "<html><body><h1>4 vues</h1><pre class=\"mermaid\">" + bloc + "</pre></body></html>";
    }

    @Test
    @DisplayName("LE CAS D'OR : la vue réseau réelle du 2026-09-26 est refusée")
    void theRealNetworkViewIsRefused() {
        var markers = CloudArchitectureInMermaid.handDrawnCloud(page(VUE_RESEAU_REELLE));

        assertThat(markers).as("c'est exactement le schéma que le PO a jugé « pas une archi AWS »")
                .isNotEmpty();
        String refus = CloudArchitectureInMermaid.refusal(markers);
        // Le refus DIT QUOI FAIRE : sans le moteur ET le préfixe, l'agent refera la même erreur.
        assertThat(refus).contains("engine=\"cloud\"").contains("aws.eks");
        assertThat(refus).contains("Mermaid reste le bon outil");
    }

    @Test
    @DisplayName("une vue de séquence de la même page PASSE — le doute ne ferme rien")
    void arealSequenceViewPasses() {
        assertThat(CloudArchitectureInMermaid.handDrawnCloud(page(VUE_SEQUENCE_REELLE))).isEmpty();
    }

    @Test
    @DisplayName("un marqueur de SERVICE isolé ne suffit pas : « le bucket S3 des logs » n'est pas une archi")
    void oneServiceMentionIsNotAnArchitecture() {
        String bloc = "sequenceDiagram\n  Client->>API: demande\n  API->>S3: dépose le fichier\n";

        assertThat(CloudArchitectureInMermaid.handDrawnCloud(page(bloc))).isEmpty();
    }

    @Test
    @DisplayName("un flux métier sans infrastructure passe")
    void abusinessFlowPasses() {
        String bloc = "flowchart LR\n  A[Demande] --> B[Validation] --> C[Paiement]\n";

        assertThat(CloudArchitectureInMermaid.handDrawnCloud(page(bloc))).isEmpty();
    }

    @Test
    @DisplayName("un bloc Markdown clôturé est vu comme un bloc Mermaid")
    void afencedBlockIsSeenToo() {
        String html = "<html><body>\n```mermaid\nflowchart TB\n  subgraph VPC[\"VPC 10.180.165.0/24\"]\n"
                + "    EKS[\"Noeuds EKS\"]\n  end\n  EKS --> TGW[\"Transit Gateway\"]\n```\n</body></html>";

        assertThat(CloudArchitectureInMermaid.handDrawnCloud(html)).isNotEmpty();
    }

    @Test
    @DisplayName("une page sans bloc Mermaid, ou vide, passe")
    void pagesWithoutMermaidPass() {
        assertThat(CloudArchitectureInMermaid.handDrawnCloud(null)).isEmpty();
        assertThat(CloudArchitectureInMermaid.handDrawnCloud("")).isEmpty();
        assertThat(CloudArchitectureInMermaid.handDrawnCloud("<p>Le VPC 10.0.0.0/16 porte EKS.</p>"))
                .as("du texte hors bloc Mermaid n'est pas un schéma").isEmpty();
    }

    @Test
    @DisplayName("une page qui porte l'IMAGE rendue passe — c'est le but de la porte")
    void arenderedImagePasses() {
        assertThat(CloudArchitectureInMermaid.handDrawnCloud(
                "<html><body><img src=\"reseau.png\" alt=\"VPC 10.180.165.0/24 EKS S3\"></body></html>"))
                .isEmpty();
    }
}
