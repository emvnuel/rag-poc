# System Prompt Guidelines

## Objetivo
Os system prompts devem focar no comportamento esperado do assistente **sem mencionar detalhes de implementação interna** como "grafo de conhecimento", "embeddings", "chunks", "UUIDs", etc.

## Problemas a Evitar

### ❌ Exemplo Errado
```
Os documentos narram a jornada de Charlie do atraso à genialidade e declínio, 
enfatizando ética científica, isolamento e regressão. Todos os UUIDs são de 
trechos do livro [019a89a5-4475-7cb4-9be0-41a8a723b25c], indexados como 
grafo de conhecimento.
```

**Problemas:**
- Menciona "UUIDs"
- Menciona "grafo de conhecimento"
- Menciona "trechos"
- Expõe detalhes técnicos de indexação

### ✅ Exemplo Correto
```
Os documentos narram a jornada de Charlie do atraso à genialidade e declínio, 
enfatizando ética científica, isolamento e regressão.
```

## Termos a Evitar nos Prompts

### Termos Técnicos Internos
- ❌ "grafo de conhecimento" / "knowledge graph"
- ❌ "UUID" / "identificadores"
- ❌ "chunks" / "trechos indexados"
- ❌ "embeddings" / "vetores"
- ❌ "similarity search" / "busca por similaridade"
- ❌ "LightRAG"
- ❌ "entity extraction" / "extração de entidades"
- ❌ "vector storage" / "armazenamento vetorial"
- ❌ "indexação"

### Termos Adequados
- ✅ "documentos"
- ✅ "fontes"
- ✅ "informações disponíveis"
- ✅ "contexto"
- ✅ "dados fornecidos"

## Variáveis de Ambiente a Ajustar

Edite o arquivo `.env` e ajuste as seguintes variáveis:

### 1. CHAT_SYSTEM_PROMPT
Prompt principal usado quando há contexto disponível.

**Diretrizes:**
- Descreva o papel do assistente
- Explique como usar citações (sem mencionar UUIDs ou chunks)
- Enfatize precisão e fidelidade às fontes
- Não mencione tecnologias ou implementação

**Exemplo:**
```env
CHAT_SYSTEM_PROMPT="Você é um assistente prestativo que responde perguntas com base nos documentos fornecidos. Sempre cite suas fontes usando os identificadores fornecidos entre colchetes. Seja preciso e baseie suas respostas apenas no conteúdo disponível."
```

### 2. CHAT_SYSTEM_PROMPT_NO_CONTEXT
Prompt usado quando não há documentos disponíveis.

**Exemplo:**
```env
CHAT_SYSTEM_PROMPT_NO_CONTEXT="Você é um assistente prestativo. No momento, não há documentos disponíveis para consulta. Informe ao usuário que ele precisa adicionar documentos ao projeto para que você possa responder suas perguntas."
```

### 3. LightRAG Query Prompts
Ajuste os prompts de query do LightRAG:

- `LIGHTRAG_LOCAL_CHAT_SYSTEM_PROMPT`
- `LIGHTRAG_GLOBAL_CHAT_SYSTEM_PROMPT`
- `LIGHTRAG_HYBRID_CHAT_SYSTEM_PROMPT`
- `LIGHTRAG_NAIVE_CHAT_SYSTEM_PROMPT`
- `LIGHTRAG_MIX_CHAT_SYSTEM_PROMPT`
- `LIGHTRAG_BYPASS_CHAT_SYSTEM_PROMPT`

**Exemplo para GLOBAL:**
```env
LIGHTRAG_GLOBAL_CHAT_SYSTEM_PROMPT="Com base nas informações disponíveis, responda a pergunta do usuário de forma clara e objetiva. Se não houver informações suficientes, indique isso claramente."
```

### 4. Entity Extraction Prompts
Estes podem manter terminologia técnica pois são internos (não expostos ao usuário):

- `LIGHTRAG_ENTITY_EXTRACTION_SYSTEM_PROMPT`
- `LIGHTRAG_ENTITY_EXTRACTION_USER_PROMPT`

## Checklist de Validação

Ao revisar um system prompt, verifique:

- [ ] Não menciona "grafo", "graph", "knowledge graph"
- [ ] Não menciona "UUID", "identificadores únicos", "IDs técnicos"
- [ ] Não menciona "chunk", "trecho indexado", "segmento"
- [ ] Não menciona "embedding", "vetor", "similarity"
- [ ] Não menciona nomes de tecnologias (LightRAG, pgvector, etc.)
- [ ] Usa linguagem voltada ao usuário final
- [ ] Foca no comportamento esperado, não na implementação
- [ ] Mantém instruções de citação claras (mas sem detalhes técnicos)

## Exemplo Completo de Ajuste

### Antes (❌)
```env
CHAT_SYSTEM_PROMPT="Você é um assistente RAG que usa LightRAG para buscar informações em um grafo de conhecimento. Cite as fontes usando o formato [UUID:chunk-N] baseado nos chunks indexados. Use embeddings para encontrar informações relevantes."
```

### Depois (✅)
```env
CHAT_SYSTEM_PROMPT="Você é um assistente que responde perguntas com base nos documentos disponíveis. Sempre cite suas fontes usando os identificadores fornecidos entre colchetes. Seja preciso e baseie suas respostas exclusivamente nas informações disponíveis."
```

## Aplicação Imediata

Para aplicar as mudanças:

1. Edite o arquivo `.env` na raiz do projeto
2. Ajuste as variáveis mencionadas acima
3. Reinicie o serviço:
   ```bash
   docker-compose down
   docker-compose up -d
   ```

## Referência Rápida

**Linguagem Técnica (EVITAR em prompts de usuário):**
- Grafo de conhecimento → informações disponíveis
- UUID → identificador
- Chunk → parte do documento / trecho
- Embedding → (não mencionar)
- Indexação → (não mencionar)
- RAG / LightRAG → (não mencionar)

**Foco do Prompt:**
- O que o assistente deve fazer
- Como se comportar com o usuário
- Como usar citações (de forma simples)
- Quando admitir limitações
