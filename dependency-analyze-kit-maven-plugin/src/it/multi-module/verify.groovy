import groovy.json.JsonOutput
import groovy.json.JsonSlurper

def reportFile = new File(basedir, 'app/target/dependency-analyze-kit/report.json')
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

assert report.probeReports >= 1 : "probeReports=${report.probeReports}\n${pretty}"
verdict('io.github.sunjirapath.it:lib', 'USED_BYTECODE')
verdict('org.apache.commons:commons-lang3', 'UNUSED_CANDIDATE')
verdict('org.springframework.boot:spring-boot-starter', 'USED_BYTECODE')

return true
