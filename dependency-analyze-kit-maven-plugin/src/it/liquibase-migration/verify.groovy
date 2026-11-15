import groovy.json.JsonSlurper

def report = new JsonSlurper().parse(new File(basedir, 'target/dependency-analyze-kit/report.json'))
assert report.probeReports > 0 : report
def migration = report.dependencies.find { it.dependency == 'org.liquibase:liquibase-core' }
assert migration.verdict == 'IGNORED' : migration
assert migration.summary.contains('startup migrations') : migration
return true
