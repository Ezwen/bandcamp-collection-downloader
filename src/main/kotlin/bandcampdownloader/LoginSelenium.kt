package bandcampdownloader

import org.openqa.selenium.By
import org.openqa.selenium.firefox.FirefoxDriver
import org.openqa.selenium.support.ui.ExpectedConditions
import org.openqa.selenium.support.ui.WebDriverWait

fun main(args: Array<String>) {
    System.setProperty("webdriver.gecko.driver", "/home/zerwan/Applications/geckodriver-v0.21.0-linux64/geckodriver");
    val driver = FirefoxDriver()
    val wait = WebDriverWait(driver, 600)
    try {
        driver.get("https://bandcamp.com/login")

        // Find and fill username field
        val usernameField = driver.findElement(By.id("username-field"))
        usernameField.sendKeys("oWbf1axApJRg8gRKlrE7U7ZDiooJJaXYHa5ZefM_bandcamp@bousse.fr")

        // Find and fill password field
        val passwordField = driver.findElement(By.id("password-field"))
        passwordField.sendKeys("y1Dh2kttnLZYeT5RuNkylmptY")

        // Find and press submit button
        val button = driver.findElement(By.tagName("button"))
        button.click()

        // Wait until login is done
        wait.until(ExpectedConditions.titleContains("collection"))

        // Get resulting cookies
        val cookies = driver.manage().cookies
        println(cookies)

        // Quit driver
        driver.quit()


    } finally {
        // Quit driver
        driver.quit()
    }
}