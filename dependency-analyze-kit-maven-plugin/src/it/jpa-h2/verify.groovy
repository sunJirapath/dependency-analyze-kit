import groovy.json.JsonOutput
import groovy.json.JsonSlurper

def reportFile = new File(basedir, 'target/dependency-analyze-kit/report.json')
assert reportFile.isFile() : "report.json not found: ${reportFile}"
def report = new JsonSlurper().parse(reportFile)
def pretty = JsonOutput.prettyPrint(reportFile.text)

def dep = { String key ->
    def d = report.dependencies.find { it.dependency == key }
    assert d != null : "dependency ${key} not in report:\n${pretty}"
    d
}
def verdict = { String key, String expected ->
    def d = dep(key)
    assert d.verdict == expected : "${key}: expected ${expected} but was ${d.verdict} (${d.summary})\n${pretty}"
}
def usedVerdict = { String key ->
    def d = dep(key)
    assert d.verdict.startsWith('USED_') : "${key}: expected USED_* but was ${d.verdict} (${d.summary})\n${pretty}"
}
def hasEvidence = { String key, String kind, String text ->
    def d = dep(key)
    assert d.evidence.any { it.kind == kind && it.detail?.contains(text) } : "${key}: no ${kind} evidence containing '${text}'\n${pretty}"
}

usedVerdict('org.springframework.boot:spring-boot-starter-data-jpa')
hasEvidence('org.springframework.boot:spring-boot-starter-data-jpa', 'AUTOCONFIG_MATCHED', 'HibernateJpaAutoConfiguration')
verdict('com.h2database:h2', 'IGNORED')

return true
