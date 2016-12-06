@Grab('com.github.groovy-wslite:groovy-wslite:1.1.2')
@Grab(group='net.sourceforge.nekohtml', module='nekohtml', version='1.9.14') 
@Grab(group='org.simplejavamail', module='simple-java-mail', version='4.1.2') 

import org.cyberneko.html.parsers.SAXParser 
import wslite.rest.*
import org.simplejavamail.email.Email
import org.simplejavamail.mailer.Mailer
import org.simplejavamail.mailer.config.*
import javax.mail.Message.RecipientType
import groovy.util.slurpersupport.GPathResult

def config = [pnpLandingPage : 'https://disneyworld.disney.go.com/events-tours/contemporary-resort/pirates-and-pals-fireworks-voyage/'
             ,pnpAvailabilityLookupPage : 'https://disneyworld.disney.go.com/finder/schedules/type/tours/id/332144%3BentityType%3Dtour/'
             ,emailSettings :
                 [emailAddress : 'some email address'
                 ,emailPassword : 'some password'
                 ,emailSender : 'lstaples'
                 ,host : 'smtp.gmail.com'
                 ,port :587
                 ]                   
             ]
             
//I can work with a typical disney http response to get it parsed 
class DisneyHttpResponseParser{

    static GPathResult parseResponse(Response response){
        String markup =  response.getContentAsString().replaceAll("&","&amp;")  //need to manually escape some stuff within the response in order to get it to parse
        //this SAXParser is more tolerant of bad markup...the stricter default one threw up all over the markup
        def parser = new XmlSlurper( new SAXParser())
        parser.parseText(markup)
    }

}             

//contains the things required to persist a disney session across discrete http calls
class DisneySession{
    ArrayList cookies   //cookies that are established via an inital http GET request to the landing page.  All of our http calls are discrete so this serves as a bare bones 'cookie manager'
    String pep_csrf //disney variable that must be present in subsequent POST form requests.  
}             

//builds a DisneySession.....duh 
class DisneySessionBuilder{

    RESTClient client
    
    DisneySessionBuilder(String pnpLandingPage){
        client = new RESTClient(pnpLandingPage)
    }

    DisneySession buildSession(){
        def session = new DisneySession()
        
        //fire a get request into the landing page and inspect the result to get what we need to populate the sesion
        def response = client.get()
        session.pep_csrf = getPepCsrfFromResponse(response)
        session.cookies = getCookiesFromResponse(response)
        session
        
    }
    
    private String getPepCsrfFromResponse(Response response){
        GPathResult parsedMarkup =  DisneyHttpResponseParser.parseResponse(response)
        
        //value is stored in a hidden input tag so so a depth first search and return it
        parsedMarkup.'**'.find{it.@name == 'pep_csrf'}.@value
        
    }
    
    private ArrayList getCookiesFromResponse(Response response){
        //grab every response 'set-cookie' header.  they would be what is normally sent on subsequent requests
        def cookies = response.headers['Set-Cookie'].collect{it.split(";")[0]} //the split strips the path and security setting.  (we can just send em all back)
        //there are duplicates in here.  assume last one wins
        cookies.unique(true) {a,b -> (a.split("=")[0] == b.split("=")[0]) ? 0 : 1} 
        cookies
    }

}

//able to fire off a request to the availability lookup service and parse out the results
class PnpAvailablityChecker{

    RESTClient client
    //this is what gets sent from the website when I did a "real" request
    def headers = [
        'Content-Type':'application/x-www-form-urlencoded; charset=UTF-8'
        ,'Accept-Encoding':'gzip, deflate, br'
        ,'Accept-Language':'en-US,en;q=0.8'
        ,'Connection':'keep-alive'
        ,'Content-Length':'159'
        ,'DNT':'1'
        ,'Host':'disneyworld.disney.go.com'
        ,'Origin':'https://disneyworld.disney.go.com'
        ,'Referer':'https://disneyworld.disney.go.com/events-tours/contemporary-resort/pirates-and-pals-fireworks-voyage/'
        ,'User-Agent':'Mozilla/5.0 (Windows NT 6.1; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/54.0.2840.71 Safari/537.36'
        ,'X-Requested-With':'XMLHttpRequest'
    ]
    String pep_csrf
    

    PnpAvailablityChecker(String pnpAvailabilityLookupPage, DisneySession session){
        client = new RESTClient(pnpAvailabilityLookupPage)
        headers['Cookie'] = session.cookies.join('; ')
        pep_csrf = session.pep_csrf
    }
    
    String check(Date dateTocheck){
        def response = client.post([headers: headers]) {urlenc searchDate: dateTocheck.format('yyyy-MM-dd'), "pep_csrf" : pep_csrf}
        GPathResult parsedMarkup =  DisneyHttpResponseParser.parseResponse(response)
    
    
        //markup is different for a found result vs not. A found result will be in a 'time' tag and a no result found event has a span tag classed 'showtimeUnavailable
        //depth first search like usual
        def foundTime= parsedMarkup.'**'.find{it.name() == 'TIME'}
        if( foundTime )
            return foundTime.text().trim()
    
        //technically not needed but lets double check it to be sure and pass a 'not sure' value if this fails 
        def foundNoResult = parsedMarkup.'**'.find{it.name() == 'SPAN' && it.@class == "showtimeUnavailable"}
        if( foundNoResult )
            return foundNoResult.text().trim()
        else
            return 'Unable to determine availibility'
    }     
}

//light wrapper for simple java mail that stores some config and knocks down the options to be _really_ simple
class Emailer{
    String emailSender
    String emailAddress
    String host
    int port
    String emailPassword
    
    void send(String message, String subject = 'Disney Pirates and Pals Availabiltiy Report'){
        def email = new Email();
        email.addRecipient(emailSender, emailAddress, RecipientType.TO)
        email.setFromAddress(emailSender, emailAddress)
        email.setReplyToAddress(emailSender, emailAddress)
        email.setSubject(subject)
        email.setText(message)
    
        def mailer = new Mailer(
                new ServerConfig(host, port, emailAddress, emailPassword)
                ,TransportStrategy.SMTP_TLS
        )  
        
        mailer.sendMail(email)
    }
}


//TIME TO MAKE THE DONUTS!!!!!!!!

DisneySession session = new DisneySessionBuilder(config.pnpLandingPage).buildSession()
//check the current day, april 1 (first day with no availability currently) and may 12 (our target date)
def daysToCheck = [new Date()
                    ,Date.parse('MM-dd-yyyy', '04-01-2017')
                    ,Date.parse('MM-dd-yyyy', '05-12-2017')
                    ]
def checker = new PnpAvailablityChecker(config.pnpAvailabilityLookupPage,session)

def lookupResults = daysToCheck.collect {dateToCheck -> "On ${dateToCheck.format('MM-dd-yyyy')} disney reports : ${checker.check(dateToCheck)}"}

String message = lookupResults.join(System.getProperty("line.separator"))

new Emailer(config.emailSettings).send(message)

println 'message sent'
                                            
    
