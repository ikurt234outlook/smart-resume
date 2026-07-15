import { FileSearchOutlined, HistoryOutlined } from '@ant-design/icons'
import { Alert, App, Button, Empty, Input, List, Select, Space, Spin, Tag, Typography } from 'antd'
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ResponsiveModal } from '../../../components/shared/ResponsiveModal'
import type { ResumeDetail } from '../../resume/types'
import {
  createAiResumeJobAnalysis,
  getAiResumeJobAnalysis,
  listAiResumeJobAnalyses,
} from '../api/aiApi'
import type {
  AiResumeJobAnalysisResponse,
  AiResumeJobAnalysisSummary,
  AiResumeSuggestion,
} from '../types'

const { Paragraph, Text } = Typography
const { TextArea } = Input

interface ResumeJobAnalysisModalProps {
  draft: ResumeDetail
  open: boolean
  onApplyPatch: (patch: AiResumeSuggestion) => void
  onClose: () => void
  onPrepareAnalysis: () => Promise<void>
}

function formatGeneratedAt(value: string) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString()
}

function statusColor(status: string) {
  switch (status) {
    case 'matched':
    case 'strong':
      return 'green'
    case 'partial':
    case 'medium':
      return 'blue'
    case 'missing':
    case 'weak':
      return 'orange'
    default:
      return 'default'
  }
}

function severityColor(severity: string) {
  switch (severity) {
    case 'high':
      return 'red'
    case 'low':
      return 'green'
    default:
      return 'gold'
  }
}

export function ResumeJobAnalysisModal({
  draft,
  open,
  onApplyPatch,
  onClose,
  onPrepareAnalysis,
}: ResumeJobAnalysisModalProps) {
  const { t } = useTranslation('ai')
  const { message } = App.useApp()
  const [jobDescription, setJobDescription] = useState('')
  const [analyzing, setAnalyzing] = useState(false)
  const [historyLoading, setHistoryLoading] = useState(false)
  const [resultLoading, setResultLoading] = useState(false)
  const [history, setHistory] = useState<AiResumeJobAnalysisSummary[]>([])
  const [result, setResult] = useState<AiResumeJobAnalysisResponse | null>(null)

  useEffect(() => {
    if (!open) {
      return
    }

    let canceled = false
    void listAiResumeJobAnalyses(draft.id)
      .then((response) => {
        if (!canceled) {
          setHistory(response.items)
        }
      })
      .catch((error) => {
        if (!canceled) {
          void message.error(error instanceof Error ? error.message : t('jobAnalysis.historyLoadFailed'))
        }
      })
      .finally(() => {
        if (!canceled) {
          setHistoryLoading(false)
        }
      })

    return () => {
      canceled = true
    }
  }, [draft.id, message, open, t])

  function handleClose() {
    setJobDescription('')
    setHistory([])
    setHistoryLoading(true)
    setResult(null)
    onClose()
  }

  async function handleAnalyze() {
    const normalizedJobDescription = jobDescription.trim()
    if (!normalizedJobDescription) {
      void message.warning(t('jobAnalysis.jdRequired'))
      return
    }

    setAnalyzing(true)
    try {
      await onPrepareAnalysis()
      const response = await createAiResumeJobAnalysis({
        resumeId: draft.id,
        jobDescription: normalizedJobDescription,
      })
      setResult(response)
      setHistory((current) => [
        {
          analysisId: response.analysisId,
          jobDescription: normalizedJobDescription,
          score: response.score,
          summary: response.summary,
          stale: false,
          generatedAt: response.generatedAt,
        },
        ...current.filter((item) => item.analysisId !== response.analysisId),
      ].slice(0, 10))
    } catch (error) {
      void message.error(error instanceof Error ? error.message : t('jobAnalysis.analysisFailed'))
    } finally {
      setAnalyzing(false)
    }
  }

  async function handleHistorySelect(analysisId: string) {
    setResultLoading(true)
    try {
      const response = await getAiResumeJobAnalysis(draft.id, analysisId)
      setResult(response)
      const historyItem = history.find((item) => item.analysisId === analysisId)
      if (historyItem) {
        setJobDescription(historyItem.jobDescription)
      }
    } catch (error) {
      void message.error(error instanceof Error ? error.message : t('jobAnalysis.historyDetailLoadFailed'))
    } finally {
      setResultLoading(false)
    }
  }

  function handleApplySuggestion(suggestion: AiResumeSuggestion) {
    if (!result || result.stale || suggestion.status === 'applied') {
      return
    }

    onApplyPatch(suggestion)
    setResult({
      ...result,
      stale: true,
      suggestionPlan: {
        ...result.suggestionPlan,
        suggestions: result.suggestionPlan.suggestions.map((item) => (
          item.id === suggestion.id ? { ...item, status: 'applied' } : item
        )),
      },
    })
    setHistory((current) => current.map((item) => (
      item.analysisId === result.analysisId ? { ...item, stale: true } : item
    )))
    void message.success(t('jobAnalysis.appliedAndStale'))
  }

  const hasResult = result !== null

  return (
    <ResponsiveModal
      open={open}
      title={t('jobAnalysis.modalTitle')}
      onCancel={handleClose}
      footer={null}
      destroyOnHidden
      width={760}
      className="resume-job-analysis-modal"
      styles={{
        body: {
          height: '72vh',
          overflowX: 'hidden',
          overflowY: 'hidden',
        },
      }}
    >
      <div className="resume-job-analysis-panel">
        <div className="resume-job-analysis-panel__intro">
          <Tag color="blue">{t('jobAnalysis.currentResume')}</Tag>
          <Text strong>{draft.title}</Text>
          <Tag>{t('jobAnalysis.professionalReview')}</Tag>
        </div>

        <div className="resume-job-analysis-panel__composer">
          <TextArea
            rows={6}
            value={jobDescription}
            onChange={(event) => setJobDescription(event.target.value)}
            placeholder={t('jobAnalysis.jdPlaceholder')}
            maxLength={30000}
            disabled={analyzing}
          />
          <div className="resume-job-analysis-panel__actions">
            <Text type="secondary">{t('jobAnalysis.jdHint')}</Text>
            <Button
              type="primary"
              icon={<FileSearchOutlined />}
              loading={analyzing}
              onClick={() => void handleAnalyze()}
            >
              {hasResult ? t('jobAnalysis.analyzeAgain') : t('jobAnalysis.startAnalysis')}
            </Button>
          </div>
        </div>

        <div className="resume-job-analysis-panel__history">
          <Text strong>{t('jobAnalysis.historyTitle')}</Text>
          <Select
            allowClear
            className="resume-job-analysis-panel__history-select"
            placeholder={historyLoading ? t('jobAnalysis.historyLoading') : t('jobAnalysis.historyPlaceholder')}
            value={result?.analysisId}
            loading={historyLoading}
            disabled={historyLoading || history.length === 0}
            onChange={(analysisId) => {
              if (analysisId) {
                void handleHistorySelect(analysisId)
              } else {
                setResult(null)
              }
            }}
            options={history.map((item) => ({
              value: item.analysisId,
              label: t('jobAnalysis.historyOption', {
                time: formatGeneratedAt(item.generatedAt),
                score: item.score,
                stale: item.stale ? t('jobAnalysis.staleShort') : '',
              }),
            }))}
            suffixIcon={<HistoryOutlined />}
          />
        </div>

        <div className="resume-job-analysis-scroll-region">
          <Spin spinning={analyzing || resultLoading}>
            {result ? (
              <div className="resume-job-analysis-result">
                {result.stale ? (
                  <Alert
                    type="warning"
                    showIcon
                    message={t('jobAnalysis.staleTitle')}
                    description={t('jobAnalysis.staleDescription')}
                  />
                ) : null}

                <section className="resume-job-analysis-result__hero">
                  <div className="resume-job-analysis-score" aria-label={t('jobAnalysis.scoreLabel', { score: result.score })}>
                    <strong>{result.score}</strong>
                    <span>{t('jobAnalysis.scoreUnit')}</span>
                  </div>
                  <div className="resume-job-analysis-result__summary">
                    <Space wrap>
                      <Tag color="geekblue">{t('jobAnalysis.scoreCompleted')}</Tag>
                      {result.stale ? <Tag color="orange">{t('jobAnalysis.staleShort')}</Tag> : <Tag color="green">{t('jobAnalysis.current')}</Tag>}
                    </Space>
                    <Paragraph>{result.summary}</Paragraph>
                    <Text type="secondary">{t('jobAnalysis.generatedAt', { time: formatGeneratedAt(result.generatedAt) })}</Text>
                  </div>
                </section>

                <section className="resume-job-analysis-section">
                  <Text strong>{t('jobAnalysis.firstImpressionTitle')}</Text>
                  <Paragraph>{result.firstImpression}</Paragraph>
                </section>

                {result.requirementMatches.length > 0 ? (
                  <section className="resume-job-analysis-section">
                    <Text strong>{t('jobAnalysis.requirementsTitle')}</Text>
                    <List
                      className="resume-job-analysis-list"
                      dataSource={result.requirementMatches}
                      renderItem={(item, index) => (
                        <List.Item key={item.text + index}>
                          <div className="resume-job-analysis-list__content">
                            <Space wrap>
                              <Text strong>{item.text}</Text>
                              <Tag color={statusColor(item.status)}>{t('score.heatmapStatus.' + item.status, { defaultValue: item.status })}</Tag>
                              {item.importance ? <Tag>{t('score.heatmapImportance.' + item.importance, { defaultValue: item.importance })}</Tag> : null}
                            </Space>
                            {item.evidence.length > 0 ? <Text type="secondary">{item.evidence.join(' ')}</Text> : null}
                            {item.suggestion ? <Text>{item.suggestion}</Text> : null}
                          </div>
                        </List.Item>
                      )}
                    />
                  </section>
                ) : null}

                {result.sectionHeatmap.length > 0 ? (
                  <section className="resume-job-analysis-section">
                    <Text strong>{t('jobAnalysis.sectionHeatmapTitle')}</Text>
                    <div className="resume-job-analysis-heatmap">
                      {result.sectionHeatmap.map((item) => (
                        <div className="resume-job-analysis-heatmap__item" key={item.sectionKey}>
                          <div>
                            <Text strong>{t('section.' + item.sectionKey, { defaultValue: item.sectionLabel || item.sectionKey })}</Text>
                            {item.summary ? <Text type="secondary">{item.summary}</Text> : null}
                          </div>
                          <Tag color={statusColor(item.status)}>{item.score}{t('jobAnalysis.scoreUnit')}</Tag>
                        </div>
                      ))}
                    </div>
                  </section>
                ) : null}

                {result.critiques.length > 0 ? (
                  <section className="resume-job-analysis-section">
                    <Text strong>{t('jobAnalysis.critiquesTitle')}</Text>
                    <List
                      className="resume-job-analysis-list"
                      dataSource={result.critiques}
                      renderItem={(item, index) => (
                        <List.Item key={item.issue + index}>
                          <div className="resume-job-analysis-list__content">
                            <Space wrap>
                              <Tag color={severityColor(item.severity)}>{t('jobAnalysis.severity.' + item.severity, { defaultValue: item.severity })}</Tag>
                              {item.section ? <Tag>{t('section.' + item.section, { defaultValue: item.section })}</Tag> : null}
                            </Space>
                            <Text strong>{item.issue}</Text>
                            {item.rationale ? <Text type="secondary">{item.rationale}</Text> : null}
                            {item.recommendation ? <Text>{item.recommendation}</Text> : null}
                          </div>
                        </List.Item>
                      )}
                    />
                  </section>
                ) : null}

                {result.suggestionPlan.suggestions.length > 0 ? (
                  <section className="resume-job-analysis-section">
                    <Text strong>{t('jobAnalysis.suggestionsTitle', { count: result.suggestionPlan.suggestions.length })}</Text>
                    {result.suggestionPlan.summary ? <Paragraph type="secondary">{result.suggestionPlan.summary}</Paragraph> : null}
                    <List
                      className="resume-job-analysis-list"
                      dataSource={result.suggestionPlan.suggestions}
                      renderItem={(item) => (
                        <List.Item
                          key={item.id}
                          actions={[
                            <Button
                              key="apply"
                              size="small"
                              type="primary"
                              disabled={result.stale || item.status === 'applied'}
                              onClick={() => handleApplySuggestion(item)}
                            >
                              {item.status === 'applied' ? t('suggestion.applied') : t('suggestion.apply')}
                            </Button>,
                          ]}
                        >
                          <div className="resume-job-analysis-list__content">
                            <Space wrap>
                              <Tag>{t('section.' + item.section, { defaultValue: item.section })}</Tag>
                              {typeof item.index === 'number' ? <Tag>{t('suggestion.indexSuffix', { index: item.index + 1 })}</Tag> : null}
                            </Space>
                            {item.currentValue ? <Text type="secondary">{t('suggestion.currentLabel')}{item.currentValue}</Text> : null}
                            <Text>{t('suggestion.suggestedLabel')}{item.suggestedValue}</Text>
                            <Text type="secondary">{t('suggestion.rationaleLabel')}{item.rationale}</Text>
                          </div>
                        </List.Item>
                      )}
                    />
                  </section>
                ) : null}

                {result.questionsToImprove.length > 0 ? (
                  <section className="resume-job-analysis-section">
                    <Text strong>{t('jobAnalysis.questionsTitle')}</Text>
                    <ul className="resume-job-analysis-questions">
                      {result.questionsToImprove.map((question, index) => <li key={question + index}>{question}</li>)}
                    </ul>
                  </section>
                ) : null}
              </div>
            ) : (
              <div className="resume-job-analysis-empty">
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('jobAnalysis.emptyDescription')} />
              </div>
            )}
          </Spin>
        </div>
      </div>
    </ResponsiveModal>
  )
}
