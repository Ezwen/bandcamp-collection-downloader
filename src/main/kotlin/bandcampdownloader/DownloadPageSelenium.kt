package bandcampdownloader

import org.openqa.selenium.By
import org.openqa.selenium.WebDriver
import org.openqa.selenium.firefox.FirefoxDriver
import org.openqa.selenium.support.ui.ExpectedCondition
import org.openqa.selenium.support.ui.ExpectedConditions
import org.openqa.selenium.support.ui.WebDriverWait
import java.io.FileOutputStream
import java.net.URL
import java.nio.channels.Channels
import java.nio.channels.ReadableByteChannel



fun main(args: Array<String>) {
    System.setProperty("webdriver.gecko.driver", "/home/zerwan/Applications/geckodriver-v0.21.0-linux64/geckodriver");

    val driver = FirefoxDriver()
    val wait = WebDriverWait(driver, 10)
    try {
        driver.get("https://bandcamp.com/download?from=collection&payment_id=1994465229&sig=d36377b10094ec86c1bd41a5700bc7cd&sitem_id=17410034")

        // Find and click on format list toggle
        val formatListToggle = driver.findElement(By.cssSelector(".item-format"))
        formatListToggle.click()

        // Find and click and the Ogg format
        val oggLocator = By.cssSelector(".formats-container ul li:nth-of-type(5)")
        val ogg = driver.findElement(oggLocator)
        wait.until(ExpectedConditions.elementToBeClickable(oggLocator))
        ogg.click()

        // Find the download link and retrieve URL
        val locator = By.cssSelector("a.item-button")
        wait.until(ExpectedConditions.elementToBeClickable(locator))
        val download = driver.findElement(locator)
        val url = download.getAttribute("href")

        // Quit driver
        driver.quit()

        // Download
        val website = URL(url)
        val rbc = Channels.newChannel(website.openStream())
        val fos = FileOutputStream("/home/zerwan/tmp/album.zip")
        fos.channel.transferFrom(rbc, 0, java.lang.Long.MAX_VALUE)
        println(url)

    } finally {
        //Close the browser
        driver.quit()
    }
}