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

verdict('org.springframework.boot:spring-boot-starter-jdbc', 'USED_BEAN')
assert dep('org.springframework.boot:spring-boot-starter-jdbc').evidence.any { it.kind == 'BEAN' && it.detail?.contains('dataSource') } : "no BEAN evidence containing 'dataSource'\n${pretty}"
verdict('org.springframework.boot:spring-boot-starter-actuator', 'USED_AUTOCONFIG')
verdict('com.h2database:h2', 'IGNORED')

return true
