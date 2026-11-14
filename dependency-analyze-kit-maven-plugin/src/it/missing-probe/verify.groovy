import groovy.json.JsonSlurper

def report = new JsonSlurper().parse(new File(basedir, 'target/dependency-analyze-kit/report.json'))
assert report.probeReports == 0
assert report.dependencies.find { it.dependency == 'org.apache.commons:commons-lang3' }.verdict == 'IGNORED'
assert new File(basedir, 'build.log').text.contains('Incomplete dependency analysis: current full-context probe evidence required')
return true
