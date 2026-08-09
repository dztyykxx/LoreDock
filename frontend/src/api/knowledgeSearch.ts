import { requestJson } from './http'

export type KnowledgeSearchMatchedBy = 'KEYWORD' | 'SEMANTIC' | 'BOTH'
export type KnowledgeSearchMode = 'KEYWORD' | 'SEMANTIC' | 'HYBRID'

export interface KnowledgeSearchScope {
  type: 'GLOBAL' | 'PROJECT' | 'BRANCH'
  projectIdentifier: string | null
  branch: string | null
}

export interface KnowledgeSearchResult {
  documentId: number
  scope: KnowledgeSearchScope
  title: string
  snippet: string
  truncated: boolean
  format: 'MARKDOWN' | 'PLAIN_TEXT'
  tags: string[]
  source: {
    type: 'MANUAL' | 'WIKI' | 'UPLOAD'
    wikiUrl: string | null
    originalFilename: string | null
    curationNote: string | null
  }
  sourceUpdatedAt: string
  relevance: number
  matchedBy: KnowledgeSearchMatchedBy
}

export interface KnowledgeSearchResponse {
  context: KnowledgeSearchScope
  mode: KnowledgeSearchMode
  generationId: number | string
  warnings: string[]
  results: KnowledgeSearchResult[]
}

export const knowledgeSearchApi = {
  searchGlobal(query: string): Promise<KnowledgeSearchResponse> {
    const params = new URLSearchParams({
      query,
      context: 'GLOBAL',
      mode: 'HYBRID',
      limit: '10',
    })
    return requestJson<KnowledgeSearchResponse>(`/api/knowledge-search?${params}`)
  },
}
