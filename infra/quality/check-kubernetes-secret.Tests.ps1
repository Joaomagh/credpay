Describe 'Disposable Kubernetes credentials' {
    It 'preserves creation, reuse, refusal and sanitized CLI boundaries' {
        & (Join-Path $PSScriptRoot '../k8s/check-disposable-secret.ps1')
    }
}
